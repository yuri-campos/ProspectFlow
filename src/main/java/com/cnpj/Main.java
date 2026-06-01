package com.cnpj;

import com.cnpj.downloader.ReceitaDownloader;
import com.cnpj.processor.EstabelecimentoProcessor;
import com.cnpj.deduplicator.Deduplicator;
import com.cnpj.exporter.ExcelExporter;
import com.cnpj.model.Empresa;

import java.util.List;

/**
 * Ponto de entrada da aplicação cnpj-semanal.
 * Orquestra o fluxo completo: download → processamento → deduplicação → exportação → atualização do histórico.
 */
public class Main {

    /**
     * SUPOSIÇÃO 1: O histórico de CNPJs já vistos fica em "data/cnpjs_vistos.csv",
     * relativo ao diretório de execução do processo (project root).
     * Se o projeto rodar de outro working directory, ajuste este caminho.
     */
    private static final String HISTORICO_PATH = "data/cnpjs_vistos.csv";

    public static void main(String[] args) {

        System.out.println("=== Iniciando processamento semanal de CNPJs ===");

        // ---------------------------------------------------------------
        // PASSO 1 — Download dos arquivos ZIP da Receita Federal
        // ---------------------------------------------------------------

        /**
         * SUPOSIÇÃO 2: ReceitaDownloader tem um construtor padrão sem argumentos.
         * Se precisar de configurações (URL base, diretório de destino, timeout, etc.),
         * considere injetar via construtor ou arquivo de properties.
         */
        ReceitaDownloader downloader = new ReceitaDownloader();

        /**
         * SUPOSIÇÃO 3: O método de download se chama "baixar()" e não recebe parâmetros,
         * pois a URL e o destino dos ZIPs são configurados internamente na classe.
         * Retorna void — eventuais erros são lançados como exceção.
         */
        System.out.println("[1/5] Baixando arquivos ZIP da Receita Federal...");
        downloader.baixar();
        System.out.println("      Download concluído.");

        // ---------------------------------------------------------------
        // PASSO 2 — Leitura e filtragem dos dados dos estabelecimentos
        // ---------------------------------------------------------------

        /**
         * SUPOSIÇÃO 4: EstabelecimentoProcessor tem um construtor padrão sem argumentos.
         * A localização dos ZIPs baixados é resolvida internamente (mesmo diretório
         * configurado no Downloader, ou um caminho fixo como "data/downloads/").
         */
        EstabelecimentoProcessor processor = new EstabelecimentoProcessor();

        /**
         * SUPOSIÇÃO 5: O método de processamento se chama "processar()" e retorna
         * uma List<Empresa> com todos os registros lidos e filtrados dos ZIPs.
         * Nenhum parâmetro é necessário pois o diretório de entrada é interno à classe.
         */
        System.out.println("[2/5] Lendo e filtrando estabelecimentos...");
        List<Empresa> empresas = processor.processar();
        System.out.println("      " + empresas.size() + " empresa(s) lida(s) após filtragem.");

        // ---------------------------------------------------------------
        // PASSO 3 — Deduplicação: remove CNPJs já vistos em execuções anteriores
        // ---------------------------------------------------------------

        /**
         * SUPOSIÇÃO 6: Deduplicator recebe o caminho do arquivo de histórico
         * no construtor, pois é a única dependência externa que ele precisa.
         */
        Deduplicator deduplicator = new Deduplicator(HISTORICO_PATH);

        /**
         * SUPOSIÇÃO 7: O método de deduplicação se chama "deduplicar(List<Empresa>)"
         * e retorna uma nova List<Empresa> contendo apenas os CNPJs que ainda
         * NÃO estão no histórico (cnpjs_vistos.csv).
         * A lista original não é modificada.
         */
        System.out.println("[3/5] Removendo CNPJs já processados anteriormente...");
        List<Empresa> empresasNovas = deduplicator.deduplicar(empresas);
        System.out.println("      " + empresasNovas.size() + " empresa(s) nova(s) após deduplicação.");

        // ---------------------------------------------------------------
        // PASSO 4 — Exportação para XLSX
        // ---------------------------------------------------------------

        /**
         * SUPOSIÇÃO 8: ExcelExporter tem um construtor padrão sem argumentos.
         * O caminho/nome do arquivo de saída é definido internamente na classe
         * (ex: "output/cnpjs_YYYY-MM-DD.xlsx"). Se precisar de nome dinâmico
         * ou diretório configurável, adapte o construtor.
         */
        ExcelExporter exporter = new ExcelExporter();

        /**
         * SUPOSIÇÃO 9: O método de exportação se chama "exportar(List<Empresa>)"
         * e retorna void. O arquivo XLSX é gerado no caminho interno da classe.
         * Eventuais erros de I/O são propagados como exceção.
         */
        System.out.println("[4/5] Exportando resultado para XLSX...");
        exporter.exportar(empresasNovas);
        System.out.println("      Arquivo XLSX gerado com sucesso.");

        // ---------------------------------------------------------------
        // PASSO 5 — Atualização do histórico de CNPJs vistos
        // ---------------------------------------------------------------

        /**
         * SUPOSIÇÃO 10: O método de atualização do histórico se chama
         * "atualizarHistorico(List<Empresa>)" e está na classe Deduplicator,
         * pois ela já conhece o caminho do CSV e a estrutura dos dados.
         * Acrescenta ao CSV somente os CNPJs que foram processados nesta rodada
         * (empresasNovas), sem reescrever entradas anteriores.
         */
        System.out.println("[5/5] Atualizando histórico de CNPJs processados...");
        deduplicator.atualizarHistorico(empresasNovas);
        System.out.println("      Histórico atualizado em: " + HISTORICO_PATH);

        System.out.println("=== Processamento semanal concluído com sucesso! ===");
    }
}
