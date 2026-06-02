package com.cnpj.processor;

import com.cnpj.model.Empresa;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Lê e filtra os arquivos de ESTABELECIMENTOS e EMPRESAS dos Dados Abertos
 * da Receita Federal, montando objetos {@link Empresa} com dados da última semana.
 *
 * <p><b>Layout oficial (cnpj-metadados.pdf – Receita Federal):</b>
 *
 * <h3>ESTABELECIMENTOS (separador: {@code ;})</h3>
 * <pre>
 *  Col  0  – CNPJ BÁSICO          (8 dígitos)
 *  Col  1  – CNPJ ORDEM           (4 dígitos)
 *  Col  2  – CNPJ DV              (2 dígitos)
 *  Col  3  – IDENTIFICADOR MAT/FIL (1=Matriz, 2=Filial)
 *  Col  4  – NOME FANTASIA
 *  Col  5  – SITUAÇÃO CADASTRAL   (2=Ativa)
 *  Col  6  – DATA SITUAÇÃO CADASTRAL
 *  Col  7  – MOTIVO SITUAÇÃO CADASTRAL
 *  Col  8  – NOME CIDADE EXTERIOR
 *  Col  9  – PAÍS
 *  Col 10  – DATA DE INÍCIO DE ATIVIDADE  ← filtro de janela de 7 dias
 *  Col 11  – CNAE FISCAL PRINCIPAL
 *  Col 12  – CNAE FISCAL SECUNDÁRIA
 *  Col 13  – TIPO LOGRADOURO
 *  Col 14  – LOGRADOURO
 *  Col 15  – NÚMERO
 *  Col 16  – COMPLEMENTO
 *  Col 17  – BAIRRO
 *  Col 18  – CEP
 *  Col 19  – UF
 *  Col 20  – MUNICÍPIO
 *  Col 21  – DDD 1
 *  Col 22  – TELEFONE 1
 *  Col 23  – DDD 2
 *  Col 24  – TELEFONE 2
 *  Col 25  – DDD FAX
 *  Col 26  – FAX
 *  Col 27  – CORREIO ELETRÔNICO   ← e-mail
 *  Col 28  – SITUAÇÃO ESPECIAL
 *  Col 29  – DATA SITUAÇÃO ESPECIAL
 * </pre>
 *
 * <h3>EMPRESAS (separador: {@code ;})</h3>
 * <pre>
 *  Col 0 – CNPJ BÁSICO
 *  Col 1 – RAZÃO SOCIAL / NOME EMPRESARIAL  ← cruzamento
 *  Col 2 – NATUREZA JURÍDICA
 *  Col 3 – QUALIFICAÇÃO DO RESPONSÁVEL
 *  Col 4 – CAPITAL SOCIAL
 *  Col 5 – PORTE DA EMPRESA
 *  Col 6 – ENTE FEDERATIVO RESPONSÁVEL
 * </pre>
 */
public class EstabelecimentoProcessor {

    private static final Logger LOG = Logger.getLogger(EstabelecimentoProcessor.class.getName());

    private static final String SEPARADOR = ";";
    private static final DateTimeFormatter FMT_DATA = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Charset CHARSET = Charset.forName("ISO-8859-1");

    // ── índices ESTABELECIMENTOS ──────────────────────────────────────────────
    private static final int EST_CNPJ_BASICO   = 0;
    private static final int EST_CNPJ_ORDEM    = 1;
    private static final int EST_CNPJ_DV       = 2;
    private static final int EST_SITUACAO      = 5;
    private static final int EST_DATA_INICIO   = 10;
    private static final int EST_DDD1          = 21;
    private static final int EST_TEL1          = 22;
    private static final int EST_DDD2          = 23;
    private static final int EST_TEL2          = 24;
    private static final int EST_EMAIL         = 27;
    private static final int EST_TOTAL_COLUNAS = 30;

    // ── índices EMPRESAS ─────────────────────────────────────────────────────
    private static final int EMP_CNPJ_BASICO  = 0;
    private static final int EMP_RAZAO_SOCIAL = 1;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Processa todos os ZIPs de Empresas e Estabelecimentos encontrados em {@code dirTemp}.
     *
     * @param dirTemp diretório onde os ZIPs foram baixados (ex: /tmp/cnpj/)
     * @return lista de {@link Empresa} com início de atividade nos últimos 7 dias,
     *         com telefone e e-mail preenchidos e razão social resolvida
     */
    public List<Empresa> processar(String dirTemp) throws IOException {

        File dir = new File(dirTemp);

        File[] empresasZips = dir.listFiles(
                (d, name) -> name.startsWith("Empresas") && name.endsWith(".zip"));
        File[] estabelecimentosZips = dir.listFiles(
                (d, name) -> name.startsWith("Estabelecimentos") && name.endsWith(".zip"));

        if (empresasZips == null || empresasZips.length == 0) {
            throw new IOException("Nenhum arquivo Empresas*.zip encontrado em: " + dirTemp);
        }
        if (estabelecimentosZips == null || estabelecimentosZips.length == 0) {
            throw new IOException("Nenhum arquivo Estabelecimentos*.zip encontrado em: " + dirTemp);
        }

        Arrays.sort(empresasZips);
        Arrays.sort(estabelecimentosZips);

        // 1. Janela de datas: [hoje-7 dias, hoje]
        LocalDate hoje   = LocalDate.now();
        LocalDate limite = hoje.minusDays(7);
        LOG.info(String.format("Janela de filtro: %s a %s", limite, hoje));

        // 2. Carrega todas as razões sociais em memória
        LOG.info("Carregando razões sociais de " + empresasZips.length + " arquivo(s)...");
        Map<String, String> razoesSociais = new HashMap<>(30_000_000);
        for (File zip : empresasZips) {
            LOG.info("  Lendo: " + zip.getName());
            razoesSociais.putAll(carregarRazoesSociais(zip));
        }
        LOG.info(String.format("  → %,d registros carregados.", razoesSociais.size()));

        // 3. Filtra estabelecimentos de todos os ZIPs
        LOG.info("Processando " + estabelecimentosZips.length + " arquivo(s) de Estabelecimentos...");
        List<Empresa> resultado = new ArrayList<>();
        for (File zip : estabelecimentosZips) {
            LOG.info("  Lendo: " + zip.getName());
            resultado.addAll(filtrarEstabelecimentos(zip, limite, hoje, razoesSociais));
        }
        LOG.info(String.format("  → %,d empresas selecionadas.", resultado.size()));

        return resultado;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Etapa A: carrega razões sociais em memória
    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, String> carregarRazoesSociais(File zipEmpresas) throws IOException {
        Map<String, String> mapa = new HashMap<>();

        try (ZipInputStream zis = abrirZip(zipEmpresas);
             BufferedReader br  = new BufferedReader(
                     new InputStreamReader(zis, CHARSET), 1 << 16)) {

            String linha;
            long lidas = 0;

            while ((linha = br.readLine()) != null) {
                lidas++;
                String[] campos = linha.split(SEPARADOR, -1);

                if (campos.length <= EMP_RAZAO_SOCIAL) continue;

                String cnpjBasico  = limpar(campos[EMP_CNPJ_BASICO]);
                String razaoSocial = limpar(campos[EMP_RAZAO_SOCIAL]);

                if (!cnpjBasico.isEmpty()) {
                    mapa.put(cnpjBasico, razaoSocial);
                }

                if (lidas % 5_000_000 == 0) {
                    LOG.fine(String.format("  Empresas lidas: %,d", lidas));
                }
            }
        }
        return mapa;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Etapa B: filtra estabelecimentos linha a linha
    // ─────────────────────────────────────────────────────────────────────────

    private List<Empresa> filtrarEstabelecimentos(
            File zipEstabelecimentos,
            LocalDate limite,
            LocalDate hoje,
            Map<String, String> razoesSociais) throws IOException {

        List<Empresa> empresas = new ArrayList<>();

        try (ZipInputStream zis = abrirZip(zipEstabelecimentos);
             BufferedReader br  = new BufferedReader(
                     new InputStreamReader(zis, CHARSET), 1 << 16)) {

            String linha;
            long lidas     = 0;
            long descartadas = 0;

            while ((linha = br.readLine()) != null) {
                lidas++;

                String[] c = linha.split(SEPARADOR, -1);

                if (c.length < EST_TOTAL_COLUNAS) {
                    descartadas++;
                    continue;
                }

                // Filtro 1: data de início dentro da janela
                LocalDate dataInicio = parsarData(limpar(c[EST_DATA_INICIO]));
                if (dataInicio == null
                        || dataInicio.isBefore(limite)
                        || dataInicio.isAfter(hoje)) {
                    descartadas++;
                    continue;
                }

                // Filtro 2: telefone obrigatório
                String telefone = montarTelefone(
                        limpar(c[EST_DDD1]), limpar(c[EST_TEL1]),
                        limpar(c[EST_DDD2]), limpar(c[EST_TEL2]));
                if (telefone.isEmpty()) {
                    descartadas++;
                    continue;
                }

                // Filtro 3: e-mail obrigatório
                String email = limpar(c[EST_EMAIL]);
                if (email.isEmpty()) {
                    descartadas++;
                    continue;
                }

                // Montagem do CNPJ completo (14 dígitos)
                String cnpjBasico = limpar(c[EST_CNPJ_BASICO]);
                String cnpj = montarCnpj(
                        cnpjBasico,
                        limpar(c[EST_CNPJ_ORDEM]),
                        limpar(c[EST_CNPJ_DV]));

                String razaoSocial = razoesSociais.getOrDefault(cnpjBasico, "");
                String situacao    = limpar(c[EST_SITUACAO]);

                Empresa empresa = new Empresa();
                empresa.setCnpj(cnpj);
                empresa.setRazaoSocial(razaoSocial);
                empresa.setTelefone(telefone);
                empresa.setEmail(email);
                empresa.setDataAbertura(dataInicio);
                empresa.setSituacaoCadastral(situacao);

                empresas.add(empresa);

                if (lidas % 10_000_000 == 0) {
                    LOG.fine(String.format(
                            "  Lidos: %,d | Aceitos: %,d | Descartados: %,d",
                            lidas, empresas.size(), descartadas));
                }
            }

            LOG.info(String.format(
                    "Leitura concluída [%s]. Total: %,d | Aceitos: %,d | Descartados: %,d",
                    zipEstabelecimentos.getName(), lidas, empresas.size(), descartadas));
        }

        return empresas;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private ZipInputStream abrirZip(File zip) throws IOException {
        ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(zip), 1 << 16), CHARSET);

        ZipEntry entry = zis.getNextEntry();
        if (entry == null) {
            throw new IOException("ZIP vazio ou corrompido: " + zip.getName());
        }
        LOG.fine("Lendo entrada ZIP: " + entry.getName());
        return zis;
    }

    private String limpar(String valor) {
        if (valor == null) return "";
        return valor.replace("\"", "").trim();
    }

    private String montarCnpj(String basico, String ordem, String dv) {
        return padLeft(basico, 8) + padLeft(ordem, 4) + padLeft(dv, 2);
    }

    private String padLeft(String s, int tamanho) {
        if (s.length() >= tamanho) return s;
        return "00000000000000".substring(0, tamanho - s.length()) + s;
    }

    private String montarTelefone(String ddd1, String tel1, String ddd2, String tel2) {
        if (!tel1.isEmpty()) {
            return ddd1.isEmpty() ? tel1 : "(" + ddd1 + ") " + tel1;
        }
        if (!tel2.isEmpty()) {
            return ddd2.isEmpty() ? tel2 : "(" + ddd2 + ") " + tel2;
        }
        return "";
    }

    private LocalDate parsarData(String valor) {
        if (valor == null || valor.isEmpty() || valor.equals("00000000")) {
            return null;
        }
        try {
            return LocalDate.parse(valor, FMT_DATA);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
