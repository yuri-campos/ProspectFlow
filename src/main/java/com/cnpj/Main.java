package com.cnpj;

import com.cnpj.downloader.ReceitaDownloader;
import com.cnpj.processor.EstabelecimentoProcessor;
import com.cnpj.deduplicator.Deduplicator;
import com.cnpj.exporter.ExcelExporter;
import com.cnpj.model.Empresa;

import java.nio.file.Paths;
import java.time.YearMonth;
import java.util.List;

public class Main {

    public static void main(String[] args) throws Exception {

        System.out.println("=== Iniciando processamento histórico de CNPJs (Jan–" + YearMonth.now() + ") ===");

        // PASSO 1 — Download do dump mais recente da Receita Federal
        ReceitaDownloader downloader = new ReceitaDownloader();
        System.out.println("[1/N] Baixando arquivos ZIP da Receita Federal...");
        downloader.baixar();
        System.out.println("      Download concluído. Base: " + downloader.getMesRecente());

        EstabelecimentoProcessor processor = new EstabelecimentoProcessor();
        Deduplicator deduplicator = new Deduplicator(Paths.get("data", "cnpjs_vistos.csv"));
        ExcelExporter exporter = new ExcelExporter();

        YearMonth inicio = YearMonth.of(2026, 1);
        YearMonth fim    = YearMonth.now();

        int etapa = 2;
        for (YearMonth mes = inicio; !mes.isAfter(fim); mes = mes.plusMonths(1)) {

            System.out.println();
            System.out.println("[" + etapa++ + "] Processando mês: " + mes);

            // Leitura e filtragem para o mês específico
            List<Empresa> empresas = processor.processar(downloader.getDirTemp(), mes);
            System.out.println("      " + empresas.size() + " empresa(s) lida(s) após filtragem.");

            // Deduplicação
            List<Empresa> empresasNovas = deduplicator.filtrar(empresas);
            System.out.println("      " + empresasNovas.size() + " empresa(s) nova(s) após deduplicação.");

            // Exportação mensal
            exporter.exportar(empresasNovas, mes);
            System.out.println("      Arquivo XLSX gerado: empresas_" + mes + ".xlsx");

            // Atualização do histórico
            deduplicator.salvarNovos(empresasNovas);
        }

        System.out.println();
        System.out.println("=== Processamento histórico concluído com sucesso! ===");
    }
}
