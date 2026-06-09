ProspectFlow
Gerador automático de leads qualificados a partir dos Dados Abertos da Receita Federal.
Toda semana, o pipeline baixa o dump mais recente de CNPJs, filtra empresas recém-abertas com alto potencial de contratar soluções de análise de crédito e exporta uma planilha .xlsx pronta para prospecção.

Como funciona

Download — baixa os ZIPs de Estabelecimentos, Empresas e Sócios diretamente do portal de Dados Abertos da Receita Federal.
Filtragem — aplica os seguintes critérios:

Data de abertura dentro do mês corrente
Situação cadastral Ativa (02)
Apenas Matrizes
CNAE principal em divisões-alvo (financeiro, varejo, atacado, imobiliário, locação de veículos)
Telefone e e-mail presentes
Excluir MEI (natureza jurídica 2135)


Deduplicação — empresas já vistas em execuções anteriores são removidas via data/cnpjs_vistos.csv.
Exportação — gera output/empresas_<AAAA-MM>.xlsx com CNPJ, razão social, telefone, e-mail, data de abertura, UF e sócios.

Divisões CNAE monitoradas
DivisãoDescrição45Comércio e reparação de veículos46Comércio atacadista47Comércio varejista64Serviços financeiros (bancos, fintechs, cooperativas)65Seguros e previdência66Atividades auxiliares financeiras (factoring, securitizadoras)68Atividades imobiliárias77Locação de veículos e equipamentos

Estrutura do projeto
ProspectFlow/
├── src/main/java/com/cnpj/
│   ├── Main.java                        # Orquestrador principal
│   ├── downloader/ReceitaDownloader.java  # Download dos ZIPs da Receita Federal
│   ├── processor/EstabelecimentoProcessor.java  # Leitura e filtragem
│   ├── deduplicator/Deduplicator.java   # Controle de CNPJs já vistos
│   ├── exporter/ExcelExporter.java      # Geração do XLSX
│   └── model/Empresa.java               # Modelo de dados
├── data/
│   └── cnpjs_vistos.csv                 # Histórico de CNPJs já exportados
├── output/
│   └── empresas_<AAAA-MM>.xlsx          # Planilha gerada
├── .github/workflows/semanal.yml        # Automação semanal via GitHub Actions
└── pom.xml

Execução local
Pré-requisitos: Java 17+ e Maven 3.8+
bash# Compilar e gerar fat JAR
mvn package

# Executar (recomendado: heap generoso por causa dos dumps da Receita)
java -Xmx6g -jar target/cnpj-semanal.jar
O arquivo .xlsx será gerado em output/.

Automação (GitHub Actions)
O workflow .github/workflows/semanal.yml roda toda segunda-feira às 08:00 UTC. Ele também pode ser disparado manualmente pela UI do GitHub em Actions → Run workflow.
Ao final de cada execução:

O .xlsx fica disponível como artefato de build por 30 dias.
O data/cnpjs_vistos.csv atualizado é commitado de volta ao repositório (somente se houver novos CNPJs).


Tecnologias

Java 17
Apache POI 5.2.5 — geração do .xlsx
Apache Commons CSV 1.11.0 — leitura dos CSVs da Receita Federal
Apache HttpClient 5.3.1 — download dos ZIPs
Logback — logging
Maven Assembly Plugin — fat JAR executável
