ProspectFlow — Gerador de Leads por CNPJ
O que é
Ferramenta que baixa os Dados Abertos de CNPJ da Receita Federal e gera planilhas Excel mensais com empresas recém-abertas que têm alto potencial de contratar soluções de análise de crédito.

Como funciona
Download — Baixa os arquivos ZIP do dump mais recente da Receita Federal (WebDAV autenticado)
Filtragem — Para cada mês do período configurado, seleciona apenas empresas que atendem todos os critérios abaixo
Deduplicação — Garante que nenhuma empresa apareça em mais de uma planilha
Exportação — Gera um .xlsx por mês em output/
Filtros aplicados
Critério	Valor
Data de abertura	Dentro do mês alvo
Situação cadastral	Ativa (02)
Tipo	Apenas matrizes
CNAE	Divisões 45, 46, 47, 64, 65, 66, 68, 77
Contato	Telefone e e-mail obrigatórios
Excluídos	MEI (natureza jurídica 2135)
Divisões CNAE alvo: veículos, atacado, varejo, serviços financeiros, seguros, atividades auxiliares financeiras, imobiliário, locação.

Saída
Arquivos gerados em output/:

output/empresas_2026-01.xlsx
output/empresas_2026-02.xlsx
output/empresas_2026-03.xlsx
...
Cada planilha contém as colunas: CNPJ · Razão Social · Telefone · E-mail · Estado · Sócios · Data de Fundação

Como executar
Pré-requisitos: Java 17+, Maven 3.x

# 1. Compilar
mvn package -q

# 2. Rodar (recomendado 6 GB de heap por causa dos ZIPs pesados)
java -Xmx6g -jar target/cnpj-semanal.jar
Estrutura do projeto
ProspectFlow/
├── data/
│   └── cnpjs_vistos.csv        # histórico de deduplicação
├── output/                     # planilhas geradas
├── src/main/java/com/cnpj/
│   ├── Main.java
│   ├── downloader/             # download dos ZIPs da Receita
│   ├── processor/              # leitura, filtragem e montagem das empresas
│   ├── deduplicator/           # controle de CNPJs já processados
│   ├── exporter/               # geração do Excel
│   └── model/                  # classe Empresa
└── pom.xml
Fonte dos dados
Dados Abertos CNPJ — Receita Federal
Atualização mensal. Token de acesso público permanente (YggdBLfdninEJX9).

Zerando o histórico
Para reprocessar tudo do zero, limpe o arquivo de deduplicação antes de rodar:

# Windows (PowerShell)
Set-Content -Path "data\cnpjs_vistos.csv" -Value "cnpj,data_primeira_vez" -Encoding utf8
