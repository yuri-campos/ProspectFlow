package com.cnpj.deduplicator;

import com.cnpj.model.Empresa;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Garante que o mesmo CNPJ não seja exportado em duas semanas diferentes.
 *
 * <p><b>Arquivo de histórico:</b> {@code data/cnpjs_vistos.csv}
 * <pre>
 *   cnpj,data_primeira_vez
 *   12345678000199,2025-06-01
 *   98765432000188,2025-06-01
 * </pre>
 *
 * <p><b>Crescimento esperado:</b> 1.000–5.000 linhas/semana → ~250.000 linhas/ano.
 * Nessa escala, um {@link HashSet} em memória é a estrutura ideal
 * (lookup O(1), consumo ~20 MB para 250 k entradas de 14 chars).
 */
public class Deduplicator {

    private static final Logger LOG = Logger.getLogger(Deduplicator.class.getName());

    private static final String CABECALHO      = "cnpj,data_primeira_vez";
    private static final String SEPARADOR      = ",";
    private static final int    COL_CNPJ       = 0;
    private static final int    COL_DATA       = 1;

    /** Caminho padrão do histórico persistido. */
    private static final Path HISTORICO_PADRAO =
            Paths.get("data", "cnpjs_vistos.csv");

    // ─────────────────────────────────────────────────────────────────────────

    private final Path            arquivoHistorico;
    private final Set<String>     cnpjsConhecidos;  // estado em memória

    // ─────────────────────────────────────────────────────────────────────────
    // Construtores
    // ─────────────────────────────────────────────────────────────────────────

    /** Usa o caminho padrão {@code data/cnpjs_vistos.csv}. */
    public Deduplicator() throws IOException {
        this(HISTORICO_PADRAO);
    }

    /**
     * Permite injetar um caminho alternativo (útil para testes).
     *
     * @param arquivoHistorico caminho completo para o CSV de histórico
     */
    public Deduplicator(Path arquivoHistorico) throws IOException {
        this.arquivoHistorico = arquivoHistorico;
        this.cnpjsConhecidos  = new HashSet<>();
        inicializar();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inicialização
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Carrega o CSV de histórico para o {@link HashSet} em memória.
     * Se o arquivo ainda não existir, cria o diretório e o arquivo com cabeçalho.
     */
    private void inicializar() throws IOException {
        if (!Files.exists(arquivoHistorico)) {
            criarArquivoVazio();
            LOG.info("Histórico não encontrado. Arquivo criado em: " + arquivoHistorico);
            return;
        }

        carregarHistorico();
        LOG.info(String.format(
                "Histórico carregado: %,d CNPJs conhecidos (%s).",
                cnpjsConhecidos.size(), arquivoHistorico));
    }

    /** Cria o diretório {@code data/} e o CSV com cabeçalho, se necessário. */
    private void criarArquivoVazio() throws IOException {
        Files.createDirectories(arquivoHistorico.getParent());
        try (BufferedWriter bw = abrirParaEscrita(false)) {
            bw.write(CABECALHO);
            bw.newLine();
        }
    }

    /**
     * Lê o CSV linha a linha com {@link BufferedReader} e popula
     * {@link #cnpjsConhecidos}. Ignora cabeçalho e linhas malformadas.
     */
    private void carregarHistorico() throws IOException {
        long linhasMalformadas = 0;

        try (BufferedReader br = Files.newBufferedReader(arquivoHistorico, StandardCharsets.UTF_8)) {
            String linha;
            boolean primeiralinha = true;

            while ((linha = br.readLine()) != null) {
                // Pula cabeçalho
                if (primeiralinha) {
                    primeiralinha = false;
                    if (linha.startsWith("cnpj")) continue;
                }

                String[] partes = linha.split(SEPARADOR, -1);
                if (partes.length < 2) {
                    linhasMalformadas++;
                    continue;
                }

                String cnpj = partes[COL_CNPJ].trim();
                if (!cnpj.isEmpty()) {
                    cnpjsConhecidos.add(cnpj);
                }
            }
        }

        if (linhasMalformadas > 0) {
            LOG.warning(String.format(
                    "Linhas malformadas ignoradas no histórico: %d", linhasMalformadas));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // API pública
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Filtra a lista removendo empresas cujo CNPJ já apareceu em semanas anteriores.
     *
     * <p>Não altera o estado interno — {@link #salvarNovos} deve ser chamado
     * explicitamente após confirmação de que a exportação foi bem-sucedida.
     *
     * @param candidatas lista produzida pelo {@code EstabelecimentoProcessor}
     * @return sublista contendo apenas CNPJs inéditos
     */
    public List<Empresa> filtrar(List<Empresa> candidatas) {
        List<Empresa> novas = candidatas.stream()
                .filter(e -> !cnpjsConhecidos.contains(e.getCnpj()))
                .collect(Collectors.toList());

        int duplicatas = candidatas.size() - novas.size();
        LOG.info(String.format(
                "Deduplicação: %,d candidatas | %,d novas | %,d duplicatas removidas.",
                candidatas.size(), novas.size(), duplicatas));

        return novas;
    }

    /**
     * Persiste os CNPJs aprovados no CSV (modo append) e atualiza o {@link HashSet}.
     *
     * <p>Deve ser chamado <em>após</em> a exportação para o Excel ter sido
     * concluída com sucesso, evitando marcar como vistos registros que
     * porventura não chegaram ao arquivo final.
     *
     * @param aprovadas lista retornada por {@link #filtrar} após exportação bem-sucedida
     * @throws IOException em caso de falha de escrita
     */
    public void salvarNovos(List<Empresa> aprovadas) throws IOException {
        if (aprovadas.isEmpty()) {
            LOG.info("Nenhum novo CNPJ para persistir no histórico.");
            return;
        }

        String hoje = LocalDate.now().toString(); // ISO-8601: yyyy-MM-dd
        int salvos  = 0;

        try (BufferedWriter bw = abrirParaEscrita(true)) { // append = true
            for (Empresa empresa : aprovadas) {
                String cnpj = empresa.getCnpj();
                if (cnpj == null || cnpj.isBlank()) continue;

                bw.write(cnpj + SEPARADOR + hoje);
                bw.newLine();

                cnpjsConhecidos.add(cnpj); // atualiza estado em memória
                salvos++;
            }
        }

        LOG.info(String.format(
                "%,d novos CNPJs persistidos em %s. Total acumulado: %,d.",
                salvos, arquivoHistorico, cnpjsConhecidos.size()));
    }

    /**
     * Retorna uma visão não modificável do conjunto de CNPJs já conhecidos.
     * Útil para testes e inspeção em tempo de execução.
     */
    public Set<String> getCnpjsConhecidos() {
        return Collections.unmodifiableSet(cnpjsConhecidos);
    }

    /**
     * Retorna o número de CNPJs atualmente no histórico em memória.
     */
    public int totalConhecidos() {
        return cnpjsConhecidos.size();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers privados
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Abre o arquivo de histórico para escrita em UTF-8.
     *
     * @param append {@code true} para acrescentar; {@code false} para sobrescrever
     */
    private BufferedWriter abrirParaEscrita(boolean append) throws IOException {
        OpenOption[] opcoes = append
                ? new OpenOption[]{StandardOpenOption.APPEND, StandardOpenOption.CREATE}
                : new OpenOption[]{StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE};

        return new BufferedWriter(
                new OutputStreamWriter(
                        Files.newOutputStream(arquivoHistorico, opcoes),
                        StandardCharsets.UTF_8));
    }
}
