# ProspectFlow — Gerador de Leads por CNPJ

## Visão Geral

O ProspectFlow é uma ferramenta desenvolvida em Java que processa os Dados Abertos de CNPJ da Receita Federal e gera planilhas Excel contendo empresas recém-abertas com alto potencial de contratação de soluções de análise de crédito.

O sistema automatiza todo o fluxo de obtenção, filtragem e exportação dos dados, permitindo a geração de listas de prospecção atualizadas mensalmente.

---

## Funcionalidades

* Download automático dos arquivos mais recentes da Receita Federal
* Processamento de grandes volumes de dados
* Filtragem por critérios de negócio
* Deduplicação persistente entre execuções
* Exportação para planilhas Excel (.xlsx)
* Geração automática de relatórios mensais

---

## Fluxo de Processamento

1. **Download**

   * Baixa os arquivos ZIP mais recentes dos Dados Abertos CNPJ.

2. **Processamento**

   * Lê e relaciona os arquivos de empresas, estabelecimentos e sócios.

3. **Filtragem**

   * Seleciona apenas empresas que atendem aos critérios definidos.

4. **Deduplicação**

   * Remove empresas já exportadas em execuções anteriores.

5. **Exportação**

   * Gera uma planilha Excel para cada mês processado.

---

## Critérios de Seleção

| Critério           | Valor                                    |
| ------------------ | ---------------------------------------- |
| Data de abertura   | Dentro do mês processado                 |
| Situação cadastral | Ativa (02)                               |
| Tipo               | Apenas matrizes                          |
| CNAE               | Divisões 45, 46, 47, 64, 65, 66, 68 e 77 |
| Contato            | Telefone e e-mail obrigatórios           |
| Exclusão           | MEI (Natureza Jurídica 2135)             |

### CNAEs Alvo

* Comércio de veículos
* Comércio atacadista
* Comércio varejista
* Serviços financeiros
* Seguros
* Atividades auxiliares financeiras
* Atividades imobiliárias
* Locação de bens

---

## Exemplo de Saída

Arquivos gerados:

output/
├── empresas_2026-01.xlsx
├── empresas_2026-02.xlsx
├── empresas_2026-03.xlsx
└── ...

Cada planilha contém:

* CNPJ
* Razão Social
* Telefone
* E-mail
* Estado
* Sócios
* Data de Fundação

---

## Tecnologias Utilizadas

* Java 17
* Maven
* Apache POI
* WebDAV
* Dados Abertos CNPJ da Receita Federal

---

## Estrutura do Projeto

ProspectFlow/
├── data/
│   └── cnpjs_vistos.csv
├── output/
├── src/main/java/com/cnpj/
│   ├── Main.java
│   ├── downloader/
│   ├── processor/
│   ├── deduplicator/
│   ├── exporter/
│   └── model/
└── pom.xml

---

## Como Executar

### Pré-requisitos

* Java 17+
* Maven 3+

### Compilação

mvn package -q

### Execução

java -Xmx6g -jar target/cnpj-semanal.jar

> Recomenda-se 6 GB de heap devido ao volume de dados processado.

---

## Reinicializar Histórico

Para reprocessar todas as empresas desde o início:

Windows (PowerShell)

Set-Content -Path "data\cnpjs_vistos.csv" -Value "cnpj,data_primeira_vez" -Encoding utf8

---

## Desafios Técnicos

* Processamento de múltiplos arquivos compactados de grande porte
* Controle de consumo de memória durante a leitura dos dados
* Relacionamento entre empresas, estabelecimentos e sócios
* Garantia de deduplicação entre execuções
* Geração eficiente de planilhas contendo milhares de registros

---

## Fonte dos Dados

Dados Abertos CNPJ disponibilizados pela Receita Federal do Brasil.

Atualização mensal.
