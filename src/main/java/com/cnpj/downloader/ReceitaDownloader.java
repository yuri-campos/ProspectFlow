package com.cnpj.downloader;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.logging.Logger;

/**
 * Baixa os arquivos ZIP dos Dados Abertos de CNPJ da Receita Federal.
 *
 * Nova URL (desde jan/2026): https://arquivos.receitafederal.gov.br/public.php/webdav/{YYYY-MM}/
 * Autenticação: Basic Auth com o token público como usuário e senha vazia.
 *
 * Token público permanente: YggdBLfdninEJX9
 */
public class ReceitaDownloader {

    private static final Logger LOG = Logger.getLogger(ReceitaDownloader.class.getName());

    private static final String BASE_URL    = "https://arquivos.receitafederal.gov.br/public.php/webdav/";
    private static final String SHARE_TOKEN = "YggdBLfdninEJX9";

    private static final String PREFIXO_EMPRESAS         = "Empresas";
    private static final String PREFIXO_ESTABELECIMENTOS = "Estabelecimentos";
    private static final int    INDICE_INICIAL            = 0;
    private static final int    INDICE_FINAL              = 9;

    private static final String DIR_TMP  = System.getProperty("java.io.tmpdir") + "/cnpj/";
    private static final int    TIMEOUT_MS = 120_000; // 2 min — arquivos pesados

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Executa o fluxo completo: descobre a pasta do mês mais recente disponível,
     * baixa todos os ZIPs de Empresas e Estabelecimentos.
     */
    public void baixar() {
        criarDiretorioTemp();

        String mesRecente = descobrirMesRecente();
        LOG.info("Mês mais recente disponível: " + mesRecente);
        System.out.println("  Usando base de dados de: " + mesRecente);

        String urlMes = BASE_URL + mesRecente + "/";

        for (String prefixo : new String[]{PREFIXO_EMPRESAS, PREFIXO_ESTABELECIMENTOS}) {
            for (int i = INDICE_INICIAL; i <= INDICE_FINAL; i++) {
                String nomeArquivo = prefixo + i + ".zip";
                String urlCompleta = urlMes + nomeArquivo;
                Path   destino     = Path.of(DIR_TMP + nomeArquivo);

                if (Files.exists(destino) && destino.toFile().length() > 0) {
                    System.out.println("  Já existe, pulando: " + nomeArquivo);
                    continue;
                }
                System.out.println("  Baixando: " + nomeArquivo);
                baixarArquivo(urlCompleta, destino);
                System.out.println("  OK: " + nomeArquivo);
            }
        }
    }

    public String getDirTemp() {
        return DIR_TMP;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Descoberta dinâmica do mês mais recente via WebDAV
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Consulta o WebDAV raiz e retorna a pasta do mês mais recente no formato YYYY-MM.
     * Tenta os últimos 3 meses retroativamente caso o mês atual ainda não esteja publicado.
     */
    private String descobrirMesRecente() {
        YearMonth mes = YearMonth.now();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM");

        for (int tentativa = 0; tentativa < 4; tentativa++) {
            String candidato = mes.minusMonths(tentativa).format(fmt);
            if (pastaExiste(candidato)) {
                return candidato;
            }
            LOG.info("Pasta " + candidato + " não encontrada, tentando mês anterior...");
        }

        throw new RuntimeException("Nenhuma pasta de dados encontrada nos últimos 4 meses. Verifique o repositório.");
    }

    private boolean pastaExiste(String anoMes) {
        try {
            String urlStr = BASE_URL + anoMes + "/";
            HttpURLConnection conn = abrirConexao(urlStr, "HEAD");
            int status = conn.getResponseCode();
            conn.disconnect();
            return status == 200 || status == 207;
        } catch (Exception e) {
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Download
    // ─────────────────────────────────────────────────────────────────────────

    private void baixarArquivo(String urlStr, Path destino) {
        try {
            HttpURLConnection conn = abrirConexao(urlStr, "GET");
            int status = conn.getResponseCode();

            if (status != HttpURLConnection.HTTP_OK) {
                throw new RuntimeException("HTTP " + status + " ao baixar: " + urlStr);
            }

            try (InputStream in  = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(destino)) {
                in.transferTo(out);
            }

            conn.disconnect();

        } catch (IOException e) {
            throw new RuntimeException("Erro ao baixar: " + urlStr, e);
        }
    }

    /**
     * Abre uma conexão HTTP com Basic Auth usando o token público da Receita.
     * Usuário = token, senha = vazia (padrão do Nextcloud/SERPRO+).
     */
    private HttpURLConnection abrirConexao(String urlStr, String method) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);

        String credencial = SHARE_TOKEN + ":";
        String auth = "Basic " + Base64.getEncoder().encodeToString(credencial.getBytes());
        conn.setRequestProperty("Authorization", auth);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");

        return conn;
    }

    private void criarDiretorioTemp() {
        try {
            Files.createDirectories(Path.of(DIR_TMP));
            LOG.info("Diretório temporário: " + DIR_TMP);
        } catch (IOException e) {
            throw new RuntimeException("Não foi possível criar o diretório temporário: " + DIR_TMP, e);
        }
    }
}
