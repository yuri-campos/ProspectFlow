package com.cnpj.processor;

import com.cnpj.model.Empresa;

import java.io.*;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Lê e filtra os arquivos de ESTABELECIMENTOS e EMPRESAS dos Dados Abertos
 * da Receita Federal, montando objetos {@link Empresa} com perfil de alto
 * potencial para compra de soluções de análise de crédito.
 *
 * Filtros aplicados:
 *  - Data de início de atividade nos últimos 30 dias
 *  - Situação cadastral = Ativa (02)
 *  - Apenas Matrizes (identificador = 1)
 *  - CNAE principal em divisões relevantes (financeiro, varejo, atacado, imobiliário, locação)
 *  - Telefone e e-mail obrigatórios
 *  - Excluir MEI (natureza jurídica 2135)
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
    private static final int EST_MATRIZ_FILIAL = 3;
    private static final int EST_SITUACAO      = 5;
    private static final int EST_DATA_INICIO   = 10;
    private static final int EST_CNAE_PRINC    = 11;
    private static final int EST_DDD1          = 21;
    private static final int EST_TEL1          = 22;
    private static final int EST_DDD2          = 23;
    private static final int EST_TEL2          = 24;
    private static final int EST_EMAIL         = 27;
    private static final int EST_UF            = 19;
    private static final int EST_TOTAL_COLUNAS = 30;

    // ── índices EMPRESAS ─────────────────────────────────────────────────────
    private static final int EMP_CNPJ_BASICO       = 0;
    private static final int EMP_RAZAO_SOCIAL       = 1;
    private static final int EMP_NATUREZA_JURIDICA  = 2;

    // ── índices SOCIOS ───────────────────────────────────────────────────────
    private static final int SOC_CNPJ_BASICO   = 0;
    private static final int SOC_NOME          = 2;
    private static final int SOC_TOTAL_COLUNAS = 3;

    // ── Filtros de negócio ────────────────────────────────────────────────────

    /** UFs excluídas da prospecção. */
    private static final Set<String> UFS_EXCLUIDAS = new HashSet<>();

    /** Natureza jurídica do MEI — excluir por baixo potencial comercial. */
    private static final String NATUREZA_MEI = "2135";

    /** Situação cadastral Ativa. */
    private static final String SITUACAO_ATIVA = "02";

    /** Identificador de Matriz. */
    private static final String MATRIZ = "1";

    /**
     * Divisões CNAE (primeiros 2 dígitos) com alto potencial de contratar
     * soluções de análise de crédito:
     *
     *  45 – Comércio e reparação de veículos (vende a prazo)
     *  46 – Comércio atacadista (crédito B2B)
     *  47 – Comércio varejista (vende a prazo ao consumidor)
     *  64 – Serviços financeiros (bancos, financeiras, cooperativas, fintechs)
     *  65 – Seguros e previdência
     *  66 – Atividades auxiliares financeiras (factoring, securitizadoras)
     *  68 – Atividades imobiliárias (financiamento de imóveis)
     *  77 – Locação de veículos e equipamentos (crédito ao locatário)
     */
    private static final Set<String> DIVISOES_CNAE_ALVO = new HashSet<>(Arrays.asList(
            "45", "46", "47", "64", "65", "66", "68", "77"
    ));

    // ─────────────────────────────────────────────────────────────────────────

    public List<Empresa> processar(String dirTemp) throws IOException {
        return processar(dirTemp, null);
    }

    public List<Empresa> processar(String dirTemp, YearMonth mesDump) throws IOException {

        File dir = new File(dirTemp);
        File[] empresasZips        = listarZips(dir, "Empresas");
        File[] estabelecimentosZips = listarZips(dir, "Estabelecimentos");
        File[] sociosZips           = listarZipsOpcional(dir, "Socios");

        // Filtra empresas abertas dentro do mês alvo (ou mês atual se não informado).
        YearMonth mesAlvo = (mesDump != null) ? mesDump : YearMonth.now();
        LocalDate limite  = mesAlvo.atDay(1);
        LocalDate hoje    = mesAlvo.atEndOfMonth();
        LOG.info(String.format("Janela de filtro: %s a %s (mês %s)", limite, hoje, mesAlvo));

        // ── PASSO 1: coletar CNPJ básicos candidatos ──────────────────────────
        LOG.info("Passo 1/3: coletando CNPJ básicos candidatos...");
        Set<String> candidatos = new HashSet<>();
        for (File zip : estabelecimentosZips) {
            LOG.info("  Varrendo: " + zip.getName());
            candidatos.addAll(coletarCnpjsBasicos(zip, limite, hoje));
        }
        LOG.info(String.format("  → %,d CNPJ(s) básico(s) candidatos.", candidatos.size()));

        if (candidatos.isEmpty()) {
            LOG.info("Nenhum candidato encontrado. Encerrando.");
            return Collections.emptyList();
        }

        // ── PASSO 2: carregar razões sociais e identificar MEIs ───────────────
        LOG.info("Passo 2/3: carregando razões sociais e filtrando MEIs...");
        Map<String, String> razoesSociais = new HashMap<>(candidatos.size() * 2);
        Set<String> cnpjsMei = new HashSet<>();

        for (File zip : empresasZips) {
            LOG.info("  Lendo: " + zip.getName());
            carregarRazoesSociais(zip, candidatos, razoesSociais, cnpjsMei);
            if (razoesSociais.size() + cnpjsMei.size() >= candidatos.size()) break;
        }

        // Remove MEIs do conjunto de candidatos
        candidatos.removeAll(cnpjsMei);
        LOG.info(String.format("  → %,d razão(ões) social(is) | %,d MEI(s) excluído(s).",
                razoesSociais.size(), cnpjsMei.size()));

        // ── PASSO 3: montar objetos Empresa ───────────────────────────────────
        LOG.info("Passo 3/3: montando registros finais...");
        List<Empresa> resultado = new ArrayList<>();
        for (File zip : estabelecimentosZips) {
            LOG.info("  Processando: " + zip.getName());
            resultado.addAll(filtrarEstabelecimentos(zip, limite, hoje, razoesSociais, candidatos));
        }
        LOG.info(String.format("  → %,d empresa(s) selecionada(s).", resultado.size()));

        // ── PASSO 4: carregar sócios ──────────────────────────────────────────
        if (sociosZips.length > 0) {
            LOG.info("Passo 4/4: carregando sócios...");
            Set<String> cnpjsFinais = new HashSet<>();
            for (Empresa e : resultado) cnpjsFinais.add(e.getCnpj().replaceAll("[^0-9]", "").substring(0, 8));
            Map<String, List<String>> socios = new HashMap<>();
            for (File zip : sociosZips) {
                LOG.info("  Lendo: " + zip.getName());
                carregarSocios(zip, cnpjsFinais, socios);
            }
            for (Empresa e : resultado) {
                String cnpjBasico = e.getCnpj().replaceAll("[^0-9]", "").substring(0, 8);
                List<String> nomes = socios.getOrDefault(cnpjBasico, Collections.emptyList());
                e.setSocios(String.join(", ", nomes));
            }
            LOG.info(String.format("  → sócios carregados para %,d empresa(s).", socios.size()));
        }

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

                // Filtro: data de início
                LocalDate dataInicio = parsarData(limpar(c[EST_DATA_INICIO]));
                if (dataInicio == null || dataInicio.isBefore(limite) || dataInicio.isAfter(hoje)) continue;

                // Filtro: situação ativa
                if (!SITUACAO_ATIVA.equals(limpar(c[EST_SITUACAO]))) continue;

                // Filtro: apenas matrizes
                if (!MATRIZ.equals(limpar(c[EST_MATRIZ_FILIAL]))) continue;

                // Filtro: UF não excluída
                if (UFS_EXCLUIDAS.contains(limpar(c[EST_UF]))) continue;

                // Filtro: CNAE alvo
                String cnae = limpar(c[EST_CNAE_PRINC]);
                if (cnae.length() < 2 || !DIVISOES_CNAE_ALVO.contains(cnae.substring(0, 2))) continue;

                // Filtro: telefone obrigatório
                String telefone = montarTelefone(
                        limpar(c[EST_DDD1]), limpar(c[EST_TEL1]),
                        limpar(c[EST_DDD2]), limpar(c[EST_TEL2]));
                if (telefone.isEmpty()) continue;

                // Filtro: e-mail obrigatório
                if (limpar(c[EST_EMAIL]).isEmpty()) continue;

                cnpjs.add(limpar(c[EST_CNPJ_BASICO]));
            }
        }
        return cnpjs;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Passo 2: carregar razões sociais e identificar MEIs
    // ─────────────────────────────────────────────────────────────────────────

    private void carregarRazoesSociais(File zipEmpresas, Set<String> candidatos,
                                       Map<String, String> destino,
                                       Set<String> cnpjsMei) throws IOException {

        try (ZipInputStream zis = abrirZip(zipEmpresas);
             BufferedReader br  = new BufferedReader(new InputStreamReader(zis, CHARSET), 1 << 16)) {

            String linha;
            while ((linha = br.readLine()) != null) {
                String[] campos = linha.split(SEPARADOR, -1);
                if (campos.length <= EMP_NATUREZA_JURIDICA) continue;

                String cnpjBasico = limpar(campos[EMP_CNPJ_BASICO]);
                if (!candidatos.contains(cnpjBasico)) continue;

                // Filtro: excluir MEI
                if (NATUREZA_MEI.equals(limpar(campos[EMP_NATUREZA_JURIDICA]))) {
                    cnpjsMei.add(cnpjBasico);
                } else {
                    destino.put(cnpjBasico, limpar(campos[EMP_RAZAO_SOCIAL]));
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Passo 3: montar objetos Empresa
    // ─────────────────────────────────────────────────────────────────────────

    private List<Empresa> filtrarEstabelecimentos(File zip, LocalDate limite, LocalDate hoje,
                                                  Map<String, String> razoesSociais,
                                                  Set<String> candidatosValidos) throws IOException {
        List<Empresa> empresas = new ArrayList<>();

        try (ZipInputStream zis = abrirZip(zip);
             BufferedReader br  = new BufferedReader(new InputStreamReader(zis, CHARSET), 1 << 16)) {

            String linha;
            while ((linha = br.readLine()) != null) {
                String[] c = linha.split(SEPARADOR, -1);
                if (c.length < EST_TOTAL_COLUNAS) continue;

                String cnpjBasico = limpar(c[EST_CNPJ_BASICO]);

                // Só processa CNPJs que sobreviveram a todos os filtros
                if (!candidatosValidos.contains(cnpjBasico)) continue;

                LocalDate dataInicio = parsarData(limpar(c[EST_DATA_INICIO]));
                if (dataInicio == null || dataInicio.isBefore(limite) || dataInicio.isAfter(hoje)) continue;

                if (!SITUACAO_ATIVA.equals(limpar(c[EST_SITUACAO]))) continue;
                if (!MATRIZ.equals(limpar(c[EST_MATRIZ_FILIAL]))) continue;
                if (UFS_EXCLUIDAS.contains(limpar(c[EST_UF]))) continue;

                String cnae = limpar(c[EST_CNAE_PRINC]);
                if (cnae.length() < 2 || !DIVISOES_CNAE_ALVO.contains(cnae.substring(0, 2))) continue;

                String telefone = montarTelefone(
                        limpar(c[EST_DDD1]), limpar(c[EST_TEL1]),
                        limpar(c[EST_DDD2]), limpar(c[EST_TEL2]));
                if (telefone.isEmpty()) continue;

                String email = limpar(c[EST_EMAIL]);
                if (email.isEmpty()) continue;

                String cnpj = montarCnpj(cnpjBasico, limpar(c[EST_CNPJ_ORDEM]), limpar(c[EST_CNPJ_DV]));

                Empresa empresa = new Empresa();
                empresa.setCnpj(cnpj);
                empresa.setRazaoSocial(razoesSociais.getOrDefault(cnpjBasico, ""));
                empresa.setTelefone(telefone);
                empresa.setEmail(email);
                empresa.setDataAbertura(dataInicio);
                empresa.setSituacaoCadastral(limpar(c[EST_SITUACAO]));
                empresa.setUf(limpar(c[EST_UF]));

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

    private File[] listarZipsOpcional(File dir, String prefixo) {
        File[] zips = dir.listFiles((d, name) -> name.startsWith(prefixo) && name.endsWith(".zip"));
        if (zips == null) return new File[0];
        Arrays.sort(zips);
        return zips;
    }

    private void carregarSocios(File zip, Set<String> cnpjsAlvo,
                                 Map<String, List<String>> socios) throws IOException {
        try (ZipInputStream zis = abrirZip(zip);
             BufferedReader br  = new BufferedReader(new InputStreamReader(zis, CHARSET), 1 << 16)) {
            String linha;
            while ((linha = br.readLine()) != null) {
                String[] c = linha.split(SEPARADOR, -1);
                if (c.length < SOC_TOTAL_COLUNAS) continue;
                String cnpjBasico = limpar(c[SOC_CNPJ_BASICO]);
                if (!cnpjsAlvo.contains(cnpjBasico)) continue;
                String nome = limpar(c[SOC_NOME]);
                if (!nome.isEmpty()) {
                    socios.computeIfAbsent(cnpjBasico, k -> new ArrayList<>()).add(nome);
                }
            }
        }
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
