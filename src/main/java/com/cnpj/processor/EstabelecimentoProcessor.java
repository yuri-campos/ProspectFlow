package com.cnpj.processor;

import com.cnpj.model.Empresa;

import java.io.*;
import java.nio.charset.Charset;
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
 * Estratégia de dois passos para evitar OutOfMemoryError:
 *   1. Varre os Estabelecimentos e coleta apenas os CNPJ básicos que passam
 *      nos filtros (data, telefone, e-mail). Resultado: poucos milhares de CNPJs.
 *   2. Varre os Empresas e carrega razões sociais APENAS desses CNPJs.
 *   3. Varre os Estabelecimentos novamente montando os objetos Empresa finais.
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

    public List<Empresa> processar(String dirTemp) throws IOException {

        File dir = new File(dirTemp);

        File[] empresasZips = listarZips(dir, "Empresas");
        File[] estabelecimentosZips = listarZips(dir, "Estabelecimentos");

        LocalDate hoje   = LocalDate.now();
        LocalDate limite = hoje.minusDays(30);
        LOG.info(String.format("Janela de filtro: %s a %s", limite, hoje));

        // ── PASSO 1: coletar CNPJ básicos que passam nos filtros ──────────────
        LOG.info("Passo 1/3: coletando CNPJ básicos candidatos...");
        Set<String> cnpjsBasicosCandidatos = new HashSet<>();
        for (File zip : estabelecimentosZips) {
            LOG.info("  Varrendo: " + zip.getName());
            cnpjsBasicosCandidatos.addAll(coletarCnpjsBasicos(zip, limite, hoje));
        }
        LOG.info(String.format("  → %,d CNPJ(s) básico(s) candidatos.", cnpjsBasicosCandidatos.size()));

        if (cnpjsBasicosCandidatos.isEmpty()) {
            LOG.info("Nenhum candidato encontrado. Encerrando.");
            return Collections.emptyList();
        }

        // ── PASSO 2: carregar razões sociais só dos candidatos ────────────────
        LOG.info("Passo 2/3: carregando razões sociais dos candidatos...");
        Map<String, String> razoesSociais = new HashMap<>(cnpjsBasicosCandidatos.size() * 2);
        for (File zip : empresasZips) {
            LOG.info("  Lendo: " + zip.getName());
            carregarRazoesSociais(zip, cnpjsBasicosCandidatos, razoesSociais);
            // Se já encontrou todos, interrompe cedo
            if (razoesSociais.size() >= cnpjsBasicosCandidatos.size()) break;
        }
        LOG.info(String.format("  → %,d razão(ões) social(is) carregada(s).", razoesSociais.size()));

        // ── PASSO 3: montar os objetos Empresa ────────────────────────────────
        LOG.info("Passo 3/3: montando registros finais...");
        List<Empresa> resultado = new ArrayList<>();
        for (File zip : estabelecimentosZips) {
            LOG.info("  Processando: " + zip.getName());
            resultado.addAll(filtrarEstabelecimentos(zip, limite, hoje, razoesSociais));
        }
        LOG.info(String.format("  → %,d empresa(s) selecionada(s).", resultado.size()));

        return resultado;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Passo 1: coletar CNPJ básicos candidatos
    // ─────────────────────────────────────────────────────────────────────────

    private Set<String> coletarCnpjsBasicos(File zip, LocalDate limite, LocalDate hoje) throws IOException {
        Set<String> cnpjs = new HashSet<>();

        try (ZipInputStream zis = abrirZip(zip);
             BufferedReader br  = new BufferedReader(new InputStreamReader(zis, CHARSET), 1 << 16)) {

            String linha;
            while ((linha = br.readLine()) != null) {
                String[] c = linha.split(SEPARADOR, -1);
                if (c.length < EST_TOTAL_COLUNAS) continue;

                LocalDate dataInicio = parsarData(limpar(c[EST_DATA_INICIO]));
                if (dataInicio == null || dataInicio.isBefore(limite) || dataInicio.isAfter(hoje)) continue;

                String telefone = montarTelefone(
                        limpar(c[EST_DDD1]), limpar(c[EST_TEL1]),
                        limpar(c[EST_DDD2]), limpar(c[EST_TEL2]));
                if (telefone.isEmpty()) continue;

                if (limpar(c[EST_EMAIL]).isEmpty()) continue;

                cnpjs.add(limpar(c[EST_CNPJ_BASICO]));
            }
        }
        return cnpjs;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Passo 2: carregar razões sociais só dos candidatos
    // ─────────────────────────────────────────────────────────────────────────

    private void carregarRazoesSociais(File zipEmpresas, Set<String> candidatos,
                                       Map<String, String> destino) throws IOException {

        try (ZipInputStream zis = abrirZip(zipEmpresas);
             BufferedReader br  = new BufferedReader(new InputStreamReader(zis, CHARSET), 1 << 16)) {

            String linha;
            while ((linha = br.readLine()) != null) {
                String[] campos = linha.split(SEPARADOR, -1);
                if (campos.length <= EMP_RAZAO_SOCIAL) continue;

                String cnpjBasico = limpar(campos[EMP_CNPJ_BASICO]);
                if (candidatos.contains(cnpjBasico)) {
                    destino.put(cnpjBasico, limpar(campos[EMP_RAZAO_SOCIAL]));
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Passo 3: montar objetos Empresa
    // ─────────────────────────────────────────────────────────────────────────

    private List<Empresa> filtrarEstabelecimentos(File zip, LocalDate limite, LocalDate hoje,
                                                  Map<String, String> razoesSociais) throws IOException {
        List<Empresa> empresas = new ArrayList<>();

        try (ZipInputStream zis = abrirZip(zip);
             BufferedReader br  = new BufferedReader(new InputStreamReader(zis, CHARSET), 1 << 16)) {

            String linha;
            while ((linha = br.readLine()) != null) {
                String[] c = linha.split(SEPARADOR, -1);
                if (c.length < EST_TOTAL_COLUNAS) continue;

                LocalDate dataInicio = parsarData(limpar(c[EST_DATA_INICIO]));
                if (dataInicio == null || dataInicio.isBefore(limite) || dataInicio.isAfter(hoje)) continue;

                String telefone = montarTelefone(
                        limpar(c[EST_DDD1]), limpar(c[EST_TEL1]),
                        limpar(c[EST_DDD2]), limpar(c[EST_TEL2]));
                if (telefone.isEmpty()) continue;

                String email = limpar(c[EST_EMAIL]);
                if (email.isEmpty()) continue;

                String cnpjBasico = limpar(c[EST_CNPJ_BASICO]);
                String cnpj = montarCnpj(cnpjBasico, limpar(c[EST_CNPJ_ORDEM]), limpar(c[EST_CNPJ_DV]));

                Empresa empresa = new Empresa();
                empresa.setCnpj(cnpj);
                empresa.setRazaoSocial(razoesSociais.getOrDefault(cnpjBasico, ""));
                empresa.setTelefone(telefone);
                empresa.setEmail(email);
                empresa.setDataAbertura(dataInicio);
                empresa.setSituacaoCadastral(limpar(c[EST_SITUACAO]));

                empresas.add(empresa);
            }
        }
        return empresas;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private File[] listarZips(File dir, String prefixo) throws IOException {
        File[] zips = dir.listFiles((d, name) -> name.startsWith(prefixo) && name.endsWith(".zip"));
        if (zips == null || zips.length == 0)
            throw new IOException("Nenhum arquivo " + prefixo + "*.zip encontrado em: " + dir);
        Arrays.sort(zips);
        return zips;
    }

    private ZipInputStream abrirZip(File zip) throws IOException {
        ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(zip), 1 << 16), CHARSET);
        ZipEntry entry = zis.getNextEntry();
        if (entry == null) throw new IOException("ZIP vazio ou corrompido: " + zip.getName());
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
        if (!tel1.isEmpty()) return ddd1.isEmpty() ? tel1 : "(" + ddd1 + ") " + tel1;
        if (!tel2.isEmpty()) return ddd2.isEmpty() ? tel2 : "(" + ddd2 + ") " + tel2;
        return "";
    }

    private LocalDate parsarData(String valor) {
        if (valor == null || valor.isEmpty() || valor.equals("00000000")) return null;
        try {
            return LocalDate.parse(valor, FMT_DATA);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
