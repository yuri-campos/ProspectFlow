package com.cnpj.downloader;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Responsável por baixar e descompactar os arquivos públicos da Receita Federal.
 *
 * Fonte: https://dadosabertos.rfb.gov.br/CNPJ/
 *
 * A base é dividida em múltiplos ZIPs numerados (0 a 9) para Empresas e Estabelecimentos.
 * Todos os arquivos são baixados, descompactados em /tmp/ e os ZIPs deletados em seguida.
 */
public class ReceitaDownloader {

    /**
     * SUPOSIÇÃO 1: A URL base não muda entre execuções semanais.
     * Se a Receita Federal alterar o endpoint, basta atualizar esta constante.
     */
    private static final String URL_BASE = "https://dadosabertos.rfb.gov.br/CNPJ/";

    /**
     * SUPOSIÇÃO 2: Os arquivos seguem o padrão de nomenclatura
     * "Empresas{N}.zip" e "Estabelecimentos{N}.zip", onde N vai de 0 a 9.
     * Se a Receita mudar o padrão de nome, ajuste os prefixos abaixo.
     */
    private static final String PREFIXO_EMPRESAS         = "Empresas";
    private static final String PREFIXO_ESTABELECIMENTOS = "Estabelecimentos";
    private static final int    INDICE_INICIAL            = 0;
    private static final int    INDICE_FINAL              = 9;

    /**
     * SUPOSIÇÃO 3: /tmp/ é gravável no ambiente de execução (Linux/GitHub Actions).
     * Em Windows local, substitua por System.getProperty("java.io.tmpdir").
     */
    private static final String DIR_TMP = "/tmp/cnpj/";

    /**
     * SUPOSIÇÃO 4: Timeout de 60 segundos para conexão e leitura é suficiente.
     * Os arquivos da Receita podem ser grandes; ajuste se houver timeout frequente.
     */
    private static final int TIMEOUT_MS = 60_000;

    /**
     * Executa o fluxo completo: download + descompactação + limpeza dos ZIPs.
     * Chamado pelo Main.java como downloader.baixar().
     *
     * @throws RuntimeException se qualquer download ou descompactação falhar.
     */
    public void baixar() {
        criarDiretorioTemp();

        List<String> prefixos = List.of(PREFIXO_EMPRESAS, PREFIXO_ESTABELECIMENTOS);

        for (String prefixo : prefixos) {
            for (int i = INDICE_INICIAL; i <= INDICE_FINAL; i++) {
                String nomeArquivo = prefixo + i + ".zip";
                String urlCompleta = URL_BASE + nomeArquivo;
                Path   destino     = Path.of(DIR_TMP + nomeArquivo);

                System.out.println("  Baixando: " + urlCompleta);
                baixarArquivo(urlCompleta, destino);

                System.out.println("  Descompactando: " + nomeArquivo);
                descompactar(destino);

                System.out.println("  Deletando ZIP: " + nomeArquivo);
                deletarArquivo(destino);
            }
        }
    }

    /**
     * Retorna o diretório temporário onde os arquivos descompactados ficam salvos.
     * Usado pelo EstabelecimentoProcessor para localizar os CSVs extraídos.
     *
     * SUPOSIÇÃO 5: O Processor vai chamar getDirTemp() para saber onde ler os arquivos.
     * Se preferir um caminho fixo no Processor, este método pode ser removido.
     */
    public String getDirTemp() {
        return DIR_TMP;
    }

    // -----------------------------------------------------------------------
    // Métodos privados
    // -----------------------------------------------------------------------

    private void criarDiretorioTemp() {
        try {
            Files.createDirectories(Path.of(DIR_TMP));
        } catch (IOException e) {
            throw new RuntimeException("Não foi possível criar o diretório temporário: " + DIR_TMP, e);
        }
    }

    /**
     * Faz o download de uma URL para um arquivo local via HttpURLConnection.
     *
     * SUPOSIÇÃO 6: O servidor da Receita não exige autenticação nem headers especiais.
     * Se passar a exigir User-Agent ou token, adicione connection.setRequestProperty() aqui.
     */
    private void baixarArquivo(String urlStr, Path destino) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new RuntimeException("Falha ao baixar " + urlStr + " — HTTP " + status);
            }

            try (InputStream in  = connection.getInputStream();
                 OutputStream out = Files.newOutputStream(destino)) {

                in.transferTo(out);
            }

        } catch (IOException e) {
            throw new RuntimeException("Erro ao baixar arquivo: " + urlStr, e);
        }
    }

    /**
     * Descompacta um ZIP para o mesmo diretório (DIR_TMP) usando ZipInputStream nativo.
     *
     * SUPOSIÇÃO 7: Cada ZIP contém um único arquivo CSV (ou poucos arquivos).
     * Se houver subdiretórios dentro do ZIP, eles serão criados automaticamente.
     *
     * SUPOSIÇÃO 8: A codificação dos nomes de entrada no ZIP é UTF-8.
     * Se aparecerem nomes corrompidos, tente new ZipInputStream(in, Charset.forName("CP437")).
     */
    private void descompactar(Path arquivoZip) {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(arquivoZip))) {

            ZipEntry entrada;
            while ((entrada = zis.getNextEntry()) != null) {

                Path destino = Path.of(DIR_TMP).resolve(entrada.getName()).normalize();

                // Proteção contra "Zip Slip" — impede escrita fora de DIR_TMP
                if (!destino.startsWith(Path.of(DIR_TMP))) {
                    throw new RuntimeException("Entrada ZIP inválida (zip slip): " + entrada.getName());
                }

                if (entrada.isDirectory()) {
                    Files.createDirectories(destino);
                } else {
                    Files.createDirectories(destino.getParent());
                    try (OutputStream out = Files.newOutputStream(destino)) {
                        zis.transferTo(out);
                    }
                }

                zis.closeEntry();
            }

        } catch (IOException e) {
            throw new RuntimeException("Erro ao descompactar: " + arquivoZip, e);
        }
    }

    private void deletarArquivo(Path arquivo) {
        try {
            Files.deleteIfExists(arquivo);
        } catch (IOException e) {
            // Não fatal — apenas loga o aviso e continua
            System.err.println("Aviso: não foi possível deletar o ZIP: " + arquivo + " — " + e.getMessage());
        }
    }
}
