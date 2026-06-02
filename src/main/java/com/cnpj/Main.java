package com.cnpj;

import com.cnpj.downloader.ReceitaDownloader;
import com.cnpj.processor.EstabelecimentoProcessor;
import com.cnpj.deduplicator.Deduplicator;
import com.cnpj.exporter.ExcelExporter;
import com.cnpj.model.Empresa;

import java.nio.file.Paths;
import java.util.List;

public class Main {

    public static void main(String[] args) throws Exception {

        System.out.println("=== Iniciando processamento semanal de CNPJs ===");

        // PASSO 1 — Download dos arquivos ZIP da Receita Federal
        ReceitaDownloader downloader = new ReceitaDownloader();
        System.out.println("[1/5] Baixando arquivos ZIP da Receita Federal...");
        downloader.baixar();
        System.out.println("      Download concluído.");

        // PASSO 2 — Leitura e filtragem dos estabelecimentos
        EstabelecimentoProcessor processor = new EstabelecimentoProcessor();
        System.out.println("[2/5] Lendo e filtrando estabelecimentos...");
        List<Empresa> empresas = processor.processar(downloader.getDirTemp());
        System.out.println("      " + empresas.size() + " empresa(s) lida(s) após filtragem.");

        // PASSO 3 — Deduplicação
        Deduplicator deduplicator = new Deduplicator(Paths.get("data", "cnpjs_vistos.csv"));
        System.out.println("[3/5] Removendo CNPJs já processados anteriormente...");
        List<Empresa> empresasNovas = deduplicator.filtrar(empresas);
        System.out.println("      " + empresasNovas.size() + " empresa(s) nova(s) após deduplicação.");

        // PASSO 4 — Exportação para XLSX
        ExcelExporter exporter = new ExcelExporter();
        System.out.println("[4/5] Exportando resultado para XLSX...");
        exporter.exportar(empresasNovas);
        System.out.println("      Arquivo XLSX gerado com sucesso.");

        // PASSO 5 — Atualização do histórico
        System.out.println("[5/5] Atualizando histórico de CNPJs processados...");
        deduplicator.salvarNovos(empresasNovas);
        System.out.println("      Histórico atualizado.");

        System.out.println("=== Processamento semanal concluído com sucesso! ===");
    }
}
