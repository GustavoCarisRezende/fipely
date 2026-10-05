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
| `app.fipe.shared-rate-limit` (`FIPE_SHARED_RATE_LIMIT`) | `true` | Coordena slots e backoff 429 via PostgreSQL entre instâncias. Desative apenas em testes isolados sem migration V5. |
| `app.fipe.shared-max-interval-ms` (`FIPE_SHARED_MAX_INTERVAL_MS`) | `60000` | Teto do intervalo compartilhado após 429. |
| `app.fipe.max-concurrent-syncs` | `2` | Número máximo de sincronizações-filhas simultâneas. |
| `app.fipe.max-attempts` | `3` | Tentativas totais após falhas HTTP 429/5xx ou de rede. |
| `app.fipe.retry-backoff-ms` | `5000` | Espera base crescente entre tentativas; também respeita `Retry-After` se maior. |
| `app.fipe.request-timeout-seconds` | `30` | Timeout de cada chamada FIPE. |
| `app.fipe.max-backoff-ms` (`FIPE_MAX_BACKOFF_MS`) | `60000` | Teto para backoff exponencial com jitter entre retries. |
| `app.fipe.max-retry-after-ms` (`FIPE_MAX_RETRY_AFTER_MS`) | `300000` | Teto para `Retry-After` aceito em segundos ou data HTTP. |
| `management.endpoints.web.exposure.include` | `health,metrics` | Endpoints Actuator expostos; continuam protegidos pelo header `X-API-Token`. |

O limitador volta gradualmente ao intervalo mínimo configurado depois de cinco respostas FIPE bem-sucedidas (reduzindo 10% do excedente por janela). O padrão mínimo segue em `1000` ms, sem aumento automático acima dele. Um `Retry-After` maior que `app.fipe.max-retry-after-ms` causa falha segura sem retry; esse prazo excedente não é persistido globalmente, então outras instâncias podem continuar no intervalo compartilhado adaptativo. Operadores devem aumentar `app.fipe.max-retry-after-ms` se a FIPE retornar prazos maiores que precisem ser respeitados por todas as instâncias. O registry Micrometer é fornecido pelo Actuator. Métricas ficam disponíveis, autenticadas pelo filtro da API, em `/fipely-sinc-service/actuator/metrics` (por exemplo `fipe.request.duration`, `fipe.rate_limit.wait`, `fipe.attempts`, `fipe.responses` e `fipe.calls`). Métricas não incluem `jobId` para evitar cardinalidade ilimitada.
| `app.fipe.reference-period-cache-minutes` | `1440` | Cache em memória da lista de períodos da FIPE, compartilhado por sincronizações e relatório de ausentes. |
| `app.websocket.allowed-origins` | `http://localhost:*,http://127.0.0.1:*` | Origens permitidas para o WebSocket no navegador; configure a origem do seu frontend se necessário. |

Uma sincronização mensal completa pode demorar bastante: a resposta HTTP aguarda seu término. Jobs top-level ativos equivalentes são deduplicados pelo PostgreSQL 15+. **A operação de jobs é single-instance**: antes do Flyway executar, uma instância adquire advisory lock de sessão e retém uma conexão PostgreSQL até o shutdown; uma segunda instância falha startup explicitamente, sem requeue de jobs da instância ativa. Reserve ao menos uma conexão de pool além dessa conexão dedicada. O limitador FIPE, por outro lado, é compartilhado e continua global. Faça rollout serial: pare a instância antiga antes de subir a nova. A V5 não modifica jobs preexistentes; se detectar equivalentes ativos, aborta com erro explicativo. Operadores devem inspecionar e resolver manualmente os registros `queued`/`running` equivalentes em `fipe.sync_jobs` e reaplicar o startup. A mudança de schema só ocorre quando Flyway executar no banco de destino — não foi aplicada a nenhum banco real durante o desenvolvimento. O limitador reserva slots e persiste o deadline `Retry-After` em operações DB curtas, liberando a conexão antes de aguardar ou fazer HTTP. O intervalo compartilhado reduz somente após cinco respostas bem-sucedidas. Chamadas simultâneas ao mesmo recurso de catálogo não são coordenadas por recurso entre processos; single-instance evita isso no modo suportado.

## API

Base: `http://localhost:8080/fipely-sinc-service/api/v1`.

Em **todos** os endpoints, `refreshOldRecords` é um parâmetro opcional da query string com padrão `false`. Nos `POST /sync/...`, `false` coleta apenas dados ainda ausentes; `true` também reconsulta os existentes com `synced_at` de pelo menos `app.sync.min-age-days` dias atrás. Os `GET` são consultas de leitura e aceitam o parâmetro para manter o contrato uniforme, sem disparar sincronizações.

### Sincronizar

| Método e rota | Corpo JSON | Resultado |
| --- | --- | --- |
| `POST /sync/periods` | `{"referenceMonth":"2026-07"}` | Marcas, modelos, anos/combustíveis e preços dos três tipos. |
| `POST /sync/brands` | `{"referenceMonth":"2026-07","vehicleType":2,"brandCode":"80"}` | Dados da marca e de seus modelos/variantes no mês. |
| `POST /sync/models` | `{"referenceMonth":"2026-07","vehicleType":2,"brandCode":"80","modelCode":10378}` | Todas as variantes e preços desse modelo no mês. |
| `POST /sync/variants` | `{"referenceMonth":"2026-07","vehicleType":2,"brandCode":"80","modelCode":10378,"modelYear":2023,"fuelCode":"5"}` | Preço de um ano/combustível no mês. |
| `POST /sync/catalog` | `{}` ou `{"includeVariants":true,"vehicleType":2,"referenceMonth":"2026-07"}` | Catálogo sem preços; período mais recente se omitido, os três tipos se `vehicleType` omitido. |

`referenceMonth` aceita `aaaa-MM` ou `MM/aaaa` (por exemplo, `"2026-07"` ou `"07/2026"`). Os códigos são os identificadores **da FIPE**. Tipos de veículo: `1` carro, `2` moto, `3` caminhão. Exemplo:

```bash
curl -X POST 'http://localhost:8080/fipely-sinc-service/api/v1/sync/variants?refreshOldRecords=false' \
  -H "X-API-Token: $FIPELY_API_TOKEN" -H 'Content-Type: application/json' \
  -d '{"referenceMonth":"2026-07","vehicleType":2,"brandCode":"80","modelCode":10378,"modelYear":2023,"fuelCode":"5"}'
```

O resultado inclui `jobId`, mês, escopo e quantos registros foram inseridos/reconsultados em cada etapa. Não é o total de registros existentes no banco. Enquanto o `POST` espera, outra conexão pode acompanhar o trabalho pelo WebSocket ou pelos endpoints abaixo. As chamadas simultâneas idênticas recebem o mesmo `jobId`.

Na sincronização de catálogo, **`includeVariants=false` por padrão**: apenas marcas e modelos são listados, sem requisitar `ConsultarAnoModelo` ou valores. Com `true`, as variantes são consultadas por modelo, ainda **sem requisitar preços**. `sync_runs` continua reservado à sincronização completa dos preços; portanto, uma carga de catálogo não marca o mês como completo no relatório de preços. Listas já salvas são reutilizadas (inclusive listas vazias), e `refreshOldRecords=true` reconsulta somente listas antigas conforme o limite de idade. Na mesma instância, execuções sobrepostas verificam novamente cada lista/cotação após aguardar a coleta em andamento. O primeiro acesso ao período mais recente pode consultar a FIPE; os posteriores usam o cache de períodos até seu vencimento. Uma cotação pontual já atualizada retorna sem repetir as consultas às listas da FIPE.

Ao sincronizar preços **no mesmo mês e tipo** após a carga do catálogo, o serviço não repete as consultas de marcas e modelos já persistidas. Se a carga foi feita com `includeVariants=true`, também não repete a lista de anos de cada modelo: consulta apenas os preços ainda ausentes. Com o padrão `false`, a primeira sincronização de preços ainda precisa listar anos/combustíveis por modelo. Essas garantias não extrapolam para outro mês: a disponibilidade de cada lista é registrada por período. Reconsultas por idade seguem a regra de `refreshOldRecords=true`.

### Progresso de sincronização

| Método e rota | Resultado |
| --- | --- |
| `GET /sync/jobs` | Até 20 execuções ativas (`running`/`queued`). Aceita `status=completed|failed|running|queued` e `limit=1..100` para filtrar. |
| `GET /sync/jobs/{jobId}` | Estado da execução, inclusive as execuções filhas quando o escopo é `period` ou `catalog`. |
| `WS /sync/progress` | Envia um evento `snapshot` das execuções ativas ao conectar e depois eventos `progress` com as mudanças. |

`vehiclesDiscovered` cresce à medida que as variantes são listadas e **não** é um total definitivo enquanto `discoveryComplete=false`. Nas sincronizações de preços, `vehiclesProcessed` soma `vehiclesSynced` (cotação consultada) e `vehiclesSkipped` (cotação já atualizada, aproveitada do banco). Na carga de catálogo com `includeVariants=true`, esses contadores representam **variantes cadastradas ou já existentes**, não preços. Sem variantes, acompanhe `brandsDiscovered` e `modelsDiscovered`. `remainingKnown` mostra apenas as variantes já descobertas e ainda não processadas. O campo `phase` informa a etapa (`referencePeriod`, `brands`, `models`, `variants`, `prices`, `completed` ou `failed`); `currentBrand`, `currentModel` e `vehicleType` indicam onde o trabalho está. Para uma carga de catálogo ou de período completo, o registro pai agrega os tipos envolvidos e inclui `children`. Execuções interrompidas por reinício do serviço passam a `failed`.

O WebSocket usa a mesma base da API: `ws://localhost:8080/fipely-sinc-service/api/v1/sync/progress`. Clientes capazes de enviar headers devem usar `X-API-Token` no handshake. O navegador não permite esse header na API `WebSocket`; envie o mesmo token **codificado em base64url UTF-8 sem padding** como subprotocolo `token.<valor>`, junto com `fipely-progress`. Não coloque o token na URL; evite registrar o header `Sec-WebSocket-Protocol` em logs. Exemplo no navegador:

```javascript
const bytes = new TextEncoder().encode(apiToken);
const encoded = btoa(String.fromCharCode(...bytes))
  .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const socket = new WebSocket(
  'ws://localhost:8080/fipely-sinc-service/api/v1/sync/progress',
  ['fipely-progress', `token.${encoded}`]
);
socket.onmessage = event => console.log(JSON.parse(event.data));
```

Todas as rotas de status exigem `X-API-Token`, como os demais endpoints. A autenticação e o compartilhamento de trabalhos simultâneos operam por instância; não há coordenação de execuções entre várias instâncias do serviço.

No Bruno, use a URL `ws://localhost:8080/fipely-sinc-service/api/v1/sync/progress` (ou `wss://` com TLS) e envie `X-API-Token` **no handshake**. `http://` não é uma URL WebSocket, e `auth: inherit` por si só não envia esse header. Se o cliente mostrar `1006`, consulte o status HTTP do handshake e os logs do serviço: `401` indica token ausente/incorreto, `403` pode indicar origem não permitida (`app.websocket.allowed-origins`), e falhas após a conexão agora são registradas com a causa no servidor. Não compartilhe o token ou o subprotocolo `token.<valor>` em logs de diagnóstico.

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
# Execução de sincronizações

Os `POST /api/v1/sync/*` respondem `202 Accepted` com `{ "jobId": ..., "status": "queued" }` assim que o job top-level é persistido. Consulte `GET /api/v1/sync/jobs/{jobId}` para acompanhar. Pedidos equivalentes ativos compartilham o mesmo jobId. Há duas lanes: pontuais (brand/model/variant) e amplas (período/catálogo); uma vaga de worker é reservada às pontuais, para um período demorado não bloquear toda consulta pontual. Cada lane é FIFO. `FIPE_SYNC_QUEUE_WORKERS` (padrão 2) define o limite comum usado na admissão; `FIPE_SYNC_QUEUE_CAPACITY` (padrão 48) limita o backlog combinado. Fila cheia em nova submissão retorna `503 Service Unavailable`. O backlog persistido é despachado em lotes conforme há vagas, não limitado ao tamanho da fila em memória. A operação de jobs é single-instance protegida pelo advisory lock descrito acima; múltiplas instâncias não processam jobs simultaneamente.

Após reinício, jobs top-level queued/running são re-enfileirados com seu ID original e parâmetros persistidos. Filhos queued/running interrompidos são marcados failed com motivo específico e excluídos da agregação; filhos novos da tentativa retomada passam a compor o progresso. Listas, preços e stamps persistidos são reutilizados. `sync_runs` continua representando somente coleta completa de preços. Para catálogo sem `referenceMonth`, o POST escolhe o mês local mais recente sem acessar FIPE; se não há mês local, retorna `503` pedindo `referenceMonth` explícito para manter o POST não bloqueante.
