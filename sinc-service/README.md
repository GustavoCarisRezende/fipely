# Fipely — sinc-service

Serviço Spring Boot (Java 25) de coleta da FIPE. Na inicialização, o Flyway cria o schema `fipe` e suas tabelas no banco `fipely`; não execute manualmente a cópia do DDL em `docs/database.md` antes do Flyway.

## Configuração

Defina as variáveis de ambiente antes de iniciar o serviço:

```bash
export FIPELY_DB_URL='jdbc:postgresql://localhost:5432/fipely'
export FIPELY_DB_USERNAME='postgres'
export FIPELY_DB_PASSWORD='senha-do-banco'
export FIPELY_API_TOKEN='um-token-secreto'
./mvnw spring-boot:run
```

O token é obrigatório: **todas** as rotas exigem o header `X-API-Token` com o valor exato de `FIPELY_API_TOKEN`; ausência ou erro retorna HTTP 401. Não grave credenciais no repositório.

Opções em `src/main/resources/application.properties`:

| Propriedade | Padrão | Uso |
| --- | --- | --- |
| `app.sync.min-age-days` | `365` | Idade mínima para reconsulta de registros existentes. |
| `app.fipe.min-request-interval-ms` | `1000` | Intervalo global mínimo entre chamadas enviadas à FIPE, inclusive retries. |
| `app.fipe.max-concurrent-syncs` | `2` | Número máximo de sincronizações-filhas simultâneas. |
| `app.fipe.max-attempts` | `3` | Tentativas totais após falhas HTTP 429/5xx ou de rede. |
| `app.fipe.retry-backoff-ms` | `5000` | Espera base crescente entre tentativas; também respeita `Retry-After` se maior. |
| `app.fipe.request-timeout-seconds` | `30` | Timeout de cada chamada FIPE. |

Uma sincronização mensal completa pode demorar bastante: a resposta HTTP aguarda seu término. Requisições simultâneas **idênticas** compartilham a mesma execução em andamento nesta instância do serviço. Em caso de falha, os registros já salvos continuam disponíveis; uma nova requisição retoma os dados ainda ausentes.

## API

Base: `http://localhost:8080/fipely-sinc-service/api/v1`.

Em **todos** os endpoints, `refreshOldRecords` é um parâmetro opcional da query string com padrão `false`. Nos `POST /sync/...`, `false` coleta apenas dados ainda ausentes; `true` também reconsulta os existentes com `synced_at` de pelo menos `app.sync.min-age-days` dias atrás. Os `GET` são consultas de leitura e aceitam o parâmetro para manter o contrato uniforme, sem disparar sincronizações.

### Sincronizar

| Método e rota | Corpo JSON obrigatório | Resultado |
| --- | --- | --- |
| `POST /sync/periods` | `{"referenceMonth":"2026-07"}` | Marcas, modelos, anos/combustíveis e preços dos três tipos. |
| `POST /sync/brands` | `{"referenceMonth":"2026-07","vehicleType":2,"brandCode":"80"}` | Dados da marca e de seus modelos/variantes no mês. |
| `POST /sync/models` | `{"referenceMonth":"2026-07","vehicleType":2,"brandCode":"80","modelCode":10378}` | Todas as variantes e preços desse modelo no mês. |
| `POST /sync/variants` | `{"referenceMonth":"2026-07","vehicleType":2,"brandCode":"80","modelCode":10378,"modelYear":2023,"fuelCode":"5"}` | Preço de um ano/combustível no mês. |

`referenceMonth` aceita `aaaa-MM` ou `MM/aaaa` (por exemplo, `"2026-07"` ou `"07/2026"`). Os códigos são os identificadores **da FIPE**. Tipos de veículo: `1` carro, `2` moto, `3` caminhão. Exemplo:

```bash
curl -X POST 'http://localhost:8080/fipely-sinc-service/api/v1/sync/variants?refreshOldRecords=false' \
  -H "X-API-Token: $FIPELY_API_TOKEN" -H 'Content-Type: application/json' \
  -d '{"referenceMonth":"2026-07","vehicleType":2,"brandCode":"80","modelCode":10378,"modelYear":2023,"fuelCode":"5"}'
```

O resultado informa o mês, o escopo e quantos registros foram inseridos/reconsultados em cada etapa. Não é o total de registros existentes no banco.

### Consultar dados e relatórios

| Método e rota | Resultado |
| --- | --- |
| `GET /history/models/{vehicleType}/{brandCode}/{modelCode}` | Todas as cotações armazenadas de todos os anos/combustíveis do modelo. |
| `GET /history/variants/{vehicleType}/{brandCode}/{modelCode}/{modelYear}/{fuelCode}` | Histórico de uma variante específica. |
| `GET /reports/periods/absent` | Meses da lista atual da FIPE que não existem em `fipe.reference_periods`. Consulta a FIPE. |
| `GET /reports/periods/incomplete` | Meses locais e tipos (`1`–`3`) sem uma execução completa registrada. Consulta apenas o banco. |

```bash
curl -H "X-API-Token: $FIPELY_API_TOKEN" \
  'http://localhost:8080/fipely-sinc-service/api/v1/history/models/2/80/10378'
```

Para mais detalhes sobre origem e estrutura dos dados, consulte [database.md](../docs/database.md) e [fluxo.md](../docs/fluxo.md).
