package com.cnpj.exporter;

import com.cnpj.model.Empresa;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.logging.Logger;

/**
 * Gera o arquivo {@code .xlsx} semanal com os dados das empresas novas.
 *
 * <p><b>Arquivo de saída:</b> {@code output/empresas_YYYY-MM-DD.xlsx}<br>
 * <b>Aba:</b> {@code Empresas Semana DD-MM-YYYY}
 *
 * <p><b>Colunas:</b>
 * <pre>
 *   A – CNPJ            (formatado: 00.000.000/0001-00)
 *   B – Razão Social
 *   C – Telefone
 *   D – E-mail
 * </pre>
 *
 * <p><b>Cabeçalho:</b> fundo azul escuro (#1F3864), texto branco, negrito, Arial 11.
 */
public class ExcelExporter {

    private static final Logger LOG = Logger.getLogger(ExcelExporter.class.getName());

    // ── Estilo do cabeçalho ───────────────────────────────────────────────────
    private static final String COR_FUNDO_HEADER = "1F3864";   // azul escuro
    private static final String COR_TEXTO_HEADER = "FFFFFF";   // branco
    private static final String FONTE             = "Arial";
    private static final short  TAMANHO_FONTE     = 11;

    // ── Labels das colunas ────────────────────────────────────────────────────
    private static final String[] CABECALHOS = {"CNPJ", "Razão Social", "Telefone", "E-mail", "Estado", "Sócios", "Data de Fundação"};

    private static final DateTimeFormatter FMT_DATA_BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // ── Larguras mínimas (em unidades POI = 1/256 de caractere) ──────────────
    private static final int[] LARGURAS_MIN = {
            20 * 256,   // CNPJ formatado tem 18 chars
            45 * 256,   // Razão Social pode ser longa
            20 * 256,   // Telefone
            35 * 256,   // E-mail
            10 * 256,   // Estado (UF)
            50 * 256,   // Sócios
            18 * 256    // Data de Fundação
    };

    // ── Formato de datas nos nomes ────────────────────────────────────────────
    private static final DateTimeFormatter FMT_ARQUIVO = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FMT_ABA     = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private static final Path DIRETORIO_SAIDA = Paths.get("output");

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Exporta a lista de empresas para um arquivo {@code .xlsx}.
     *
     * @param empresas lista já deduplicada e filtrada
     * @return caminho do arquivo gerado
     * @throws IOException em caso de falha de escrita
     */
    public Path exportar(List<Empresa> empresas) throws IOException {
        LocalDate hoje    = LocalDate.now();
        Path arquivoSaida = resolverCaminhoSaida(hoje);

        LOG.info(String.format("Gerando Excel: %s (%,d registros)…", arquivoSaida, empresas.size()));

        Files.createDirectories(DIRETORIO_SAIDA);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {

            // ── Criar aba ─────────────────────────────────────────────────────
            String nomeAba = "Empresas Semana " + hoje.format(FMT_ABA);
            XSSFSheet sheet = workbook.createSheet(nomeAba);

            // ── Estilos ───────────────────────────────────────────────────────
            CellStyle estiloHeader = criarEstiloHeader(workbook);
            CellStyle estiloDados  = criarEstiloDados(workbook);

            // ── Linha de cabeçalho (linha 0) ──────────────────────────────────
            Row header = sheet.createRow(0);
            for (int i = 0; i < CABECALHOS.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(CABECALHOS[i]);
                cell.setCellStyle(estiloHeader);
            }

            // ── Linhas de dados ───────────────────────────────────────────────
            int linhaAtual = 1;
            for (Empresa empresa : empresas) {
                Row row = sheet.createRow(linhaAtual++);

                criarCelula(row, 0, formatarCnpj(empresa.getCnpj()),    estiloDados);
                criarCelula(row, 1, nvl(empresa.getRazaoSocial()),      estiloDados);
                criarCelula(row, 2, nvl(empresa.getTelefone()),         estiloDados);
                criarCelula(row, 3, nvl(empresa.getEmail()),            estiloDados);
                criarCelula(row, 4, nvl(empresa.getUf()),               estiloDados);
                criarCelula(row, 5, nvl(empresa.getSocios()),           estiloDados);
                String dataFundacao = empresa.getDataAbertura() != null
                        ? empresa.getDataAbertura().format(FMT_DATA_BR) : "";
                criarCelula(row, 6, dataFundacao,                       estiloDados);
            }

            // ── Largura das colunas ───────────────────────────────────────────
            ajustarColunas(sheet, empresas.size());

            // ── Congelar linha de cabeçalho ───────────────────────────────────
            sheet.createFreezePane(0, 1);

            // ── Persistir ─────────────────────────────────────────────────────
            try (OutputStream out = Files.newOutputStream(arquivoSaida)) {
                workbook.write(out);
            }
        }

        LOG.info("Arquivo gerado com sucesso: " + arquivoSaida.toAbsolutePath());
        return arquivoSaida;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Formatação do CNPJ
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Converte 14 dígitos no formato oficial:
     * {@code 12345678000199} → {@code 12.345.678/0001-99}
     *
     * <p>Se o valor já estiver formatado ou for nulo/vazio, retorna como está.
     */
    public static String formatarCnpj(String cnpj) {
        if (cnpj == null) return "";
        String digits = cnpj.replaceAll("[^0-9]", "");
        if (digits.length() != 14) return cnpj; // devolve original se inválido
        return digits.substring(0, 2)  + "."
             + digits.substring(2, 5)  + "."
             + digits.substring(5, 8)  + "/"
             + digits.substring(8, 12) + "-"
             + digits.substring(12);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Estilos
    // ─────────────────────────────────────────────────────────────────────────

    private CellStyle criarEstiloHeader(XSSFWorkbook wb) {
        XSSFCellStyle estilo = wb.createCellStyle();

        // Fundo azul escuro
        XSSFColor fundoAzul = new XSSFColor(hexToBytes(COR_FUNDO_HEADER), null);
        estilo.setFillForegroundColor(fundoAzul);
        estilo.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // Fonte branca, negrito
        XSSFFont fonte = wb.createFont();
        fonte.setFontName(FONTE);
        fonte.setFontHeightInPoints(TAMANHO_FONTE);
        fonte.setBold(true);
        fonte.setColor(new XSSFColor(hexToBytes(COR_TEXTO_HEADER), null));
        estilo.setFont(fonte);

        // Alinhamento centralizado verticalmente
        estilo.setVerticalAlignment(VerticalAlignment.CENTER);
        estilo.setAlignment(HorizontalAlignment.CENTER);

        // Bordas sutis
        estilo.setBorderBottom(BorderStyle.THIN);
        estilo.setBorderTop(BorderStyle.THIN);
        estilo.setBorderLeft(BorderStyle.THIN);
        estilo.setBorderRight(BorderStyle.THIN);

        return estilo;
    }

    private CellStyle criarEstiloDados(XSSFWorkbook wb) {
        XSSFCellStyle estilo = wb.createCellStyle();

        XSSFFont fonte = wb.createFont();
        fonte.setFontName(FONTE);
        fonte.setFontHeightInPoints(TAMANHO_FONTE);
        estilo.setFont(fonte);

        estilo.setVerticalAlignment(VerticalAlignment.CENTER);
        estilo.setAlignment(HorizontalAlignment.LEFT);

        // Borda inferior leve para separar linhas
        estilo.setBorderBottom(BorderStyle.HAIR);

        return estilo;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Largura das colunas
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Aplica {@code autoSizeColumn} somente quando há dados suficientes para
     * que o cálculo faça sentido; caso contrário usa as larguras mínimas.
     * Limita cada coluna a 60 chars para evitar colunas excessivamente largas
     * com razões sociais longas.
     */
    private void ajustarColunas(XSSFSheet sheet, int totalLinhas) {
        final int LIMITE_MAX = 60 * 256;

        for (int i = 0; i < CABECALHOS.length; i++) {
            if (totalLinhas > 0) {
                sheet.autoSizeColumn(i);
                // Aplica largura mínima se autoSize resultou em algo muito estreito
                int larguraAtual = sheet.getColumnWidth(i);
                int larguraFinal = Math.max(larguraAtual, LARGURAS_MIN[i]);
                larguraFinal     = Math.min(larguraFinal, LIMITE_MAX);
                sheet.setColumnWidth(i, larguraFinal);
            } else {
                sheet.setColumnWidth(i, LARGURAS_MIN[i]);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private Path resolverCaminhoSaida(LocalDate data) {
        String nomeArquivo = "empresas_" + data.format(FMT_ARQUIVO) + ".xlsx";
        return DIRETORIO_SAIDA.resolve(nomeArquivo);
    }

    private void criarCelula(Row row, int col, String valor, CellStyle estilo) {
        Cell cell = row.createCell(col);
        cell.setCellValue(valor);
        cell.setCellStyle(estilo);
    }

    /** Converte string hex (6 chars) em array de 3 bytes RGB. */
    private byte[] hexToBytes(String hex) {
        return new byte[]{
                (byte) Integer.parseInt(hex.substring(0, 2), 16),
                (byte) Integer.parseInt(hex.substring(2, 4), 16),
                (byte) Integer.parseInt(hex.substring(4, 6), 16)
        };
    }

    /** Null-safe: retorna string vazia se o valor for nulo. */
    private String nvl(String valor) {
        return valor == null ? "" : valor;
    }
}
