# Banco de dados do Fipely

## Escopo e fontes

Este documento descreve **somente a estrutura dos dados**: tabelas, colunas, fontes, relações, restrições e índices. A ordem das requisições, a sincronização e as consultas de histórico estão em [fluxo.md](fluxo.md).

Todas as tabelas pertencem ao schema PostgreSQL **`fipe`**. As migrações executáveis aplicadas na inicialização do serviço estão em `sinc-service/src/main/resources/db/migration/` (Flyway). O DDL abaixo documenta as estruturas; os arquivos V1 e V2 são as fontes canônicas para novas instalações.

A fonte é a API de `veiculos.fipe.org.br`. Foram executadas as cinco requisições representadas em `request-collection/fipe-org/Example/`, usando o período **julho/2026** (`Codigo = 335`), incluindo amostras de carro, moto e caminhão. Os formatos descritos a seguir foram observados nessas respostas HTTP 200; não representam uma garantia para todo o histórico da API.

## Respostas observadas e mapeamento

| Endpoint | Estrutura observada | Destino no banco |
| --- | --- | --- |
| `ConsultarTabelaDeReferencia` | Array de `{ "Codigo": 335, "Mes": "julho/2026 " }`; `Codigo` é número e `Mes` contém espaço final na amostra. | `reference_periods.external_code`, `source_month_label`; converter `Mes` para `reference_month`. |
| `ConsultarMarcas` | Array de `{ "Label": "HONDA", "Value": "80" }` para motos; `Value` é string. | `brands` e `period_brands`. |
| `ConsultarModelos` | Objeto com `Modelos` (array de `{ "Label": "CB 300F Twister Flex", "Value": 10378 }`, código numérico) **e `Anos`** (array de `{ "Label": "32000", "Value": "32000-5" }`). | `models`, `period_models`; o objeto completo fica em `model_list_responses`. |
| `ConsultarAnoModelo` | Array de `{ "Label": "2023", "Value": "2023-5" }` para o modelo de moto testado. Para um modelo de carro testado, o rótulo foi `"2023 Flex"`. | `model_variants` e `period_variants`. O `Value` original é preservado. |
| `ConsultarValorComTodosParametros` | Objeto com campos de cotação e identificação, detalhados abaixo. | `vehicle_prices`, incluindo `raw_response` integral. |

Na consulta `ConsultarModelos` de uma marca, `Anos` traz opções para a **consulta daquela marca**, enquanto `ConsultarAnoModelo` traz as opções observadas para **um modelo informado**. Por exemplo, a primeira resposta da marca HONDA continha `32000-1` e `32000-5`, mas a resposta do modelo `10378` continha apenas `32000-5`. Portanto, os valores de `Anos` não são associados automaticamente a todos os modelos: são preservados no JSON de `model_list_responses`, e `period_variants` é preenchida a partir de `ConsultarAnoModelo`.

O objeto de cotação observado para uma moto (`335`, marca `80`, modelo `10378`, ano `2023`, combustível `5`) continha:

| Campo JSON | Exemplo observado | Armazenamento |
| --- | --- | --- |
| `Valor` | `"R$ 23.519,00"` | Convertido para `price_brl`; original em `raw_response`. |
| `CodigoFipe` | `"811174-0"` | `fipe_code` e `raw_response`. É texto, não número. |
| `Marca` | `"HONDA"` | `raw_response`. |
| `Modelo` | `"CB 300F Twister Flex"` | `raw_response`. |
| `AnoModelo` | `2023` | `raw_response`; `model_variants.model_year` vem da seleção de ano/modelo. |
| `Combustivel` | `"Flex"` | `raw_response`. |
| `MesReferencia` | `"julho de 2026 "` | `raw_response`; não substitui `reference_month`. |
| `Autenticacao` | String retornada na consulta. | `raw_response`. |
| `TipoVeiculo` | `2` | `raw_response`; também representado por `brands.vehicle_type`. |
| `SiglaCombustivel` | `"F"` | `raw_response`. |
| `DataConsulta` | Texto em português com data e hora. | `raw_response`; distinto de `synced_at`, registrado pelo Fipely. |

Foram observadas cotações com esses mesmos nomes de campos para carro (Fiat/ARGO) e caminhão (SCANIA/R-450), além de uma cotação cuja seleção usou `anoModelo=32000`. Na moto testada, as cotações de `2023` e `32000` retornaram o **mesmo** `CodigoFipe` (`811174-0`), mas valores diferentes (`R$ 23.519,00` e `R$ 29.013,00`); portanto, esse código sozinho não identifica uma cotação. **O retorno não atribui significado textual a `32000`**: o banco guarda esse valor e seu `Label` sem classificá-lo como zero km.

## Convenções

- `id` é uma chave interna do Fipely; `external_code` e `fipe_code` vêm da FIPE e não devem ser confundidos com ela. O tipo de cada código respeita a resposta observada: `Codigo` do período e `Modelos[].Value` são números; `Marcas[].Value`, `Anos[].Value` e `CodigoFipe` são strings.
- `vehicle_type`: `1` = carro, `2` = moto, `3` = caminhão.
- `reference_month` usa o primeiro dia do mês: `2026-07-01` representa 07/2026. O código externo da tabela de referência é armazenado separadamente; não se calcula um a partir do outro.
- Códigos de marca e modelo são identificados em seus contextos: `(vehicle_type, external_code)` para marcas e `(brand_id, external_code)` para modelos. Uma variante corresponde a `(model_id, model_year, fuel_code)`.
- `model_year` conserva o número enviado como `anoModelo`; `source_value` conserva o `Value` integral de `ConsultarAnoModelo` (por exemplo, `32000-5`), e `display_label` conserva seu `Label`. Nas amostras, o `Value` tem formato `ano-combustível`; o importador deverá rejeitar ou tratar explicitamente outros formatos, caso surjam.
- Nomes em `brands` e `models` representam a versão canônica mais recente; os rótulos das tabelas `period_*` registram a apresentação em cada período. O retorno completo da consulta de valor fica em `raw_response`.
- Todos os registros sincronizados, **exceto `reference_periods`**, possuem `synced_at timestamptz`: instante da última coleta bem-sucedida daquele registro. `sync_runs` é uma tabela interna de execução, com `started_at` e `finished_at` em vez de `synced_at`.

## Dicionário de tabelas

### `reference_periods` — períodos de referência

Um registro por mês disponível na Tabela FIPE. **Fonte:** `ConsultarTabelaDeReferencia` (`List Períodos.yml`).

| Coluna | Descrição |
| --- | --- |
| `id` | Identificador interno. |
| `external_code` | Número de `Codigo`, enviado depois como `codigoTabelaReferencia`. |
| `source_month_label` | `Mes` textual original, incluindo eventuais espaços. |
| `reference_month` | Mês extraído de `Mes`, como data no primeiro dia do mês. |

O código externo e o mês são únicos, mas têm significados diferentes.

### `brands` — marcas

Catálogo de marcas por tipo de veículo. **Fonte:** `ConsultarMarcas` (`List Marcas.yml`).

| Coluna | Descrição |
| --- | --- |
| `id` | Identificador interno da marca. |
| `vehicle_type` | Tipo FIPE: carro (`1`), moto (`2`) ou caminhão (`3`). |
| `external_code` | String `Value`, usada depois como `codigoMarca`. |
| `name` | `Label` mais recente recebido para a marca. |
| `synced_at` | Última sincronização desta marca. |

Uma marca é identificada externamente pelo par `(vehicle_type, external_code)`, não somente pelo código.

### `models` — modelos

Catálogo de modelos associados a uma marca. **Fonte:** `ConsultarModelos` (`List Modelos.yml`).

| Coluna | Descrição |
| --- | --- |
| `id` | Identificador interno do modelo. |
| `brand_id` | Marca à qual o modelo pertence (`brands.id`). |
| `external_code` | Número `Modelos[].Value`, usado depois como `codigoModelo`. |
| `name` | `Modelos[].Label` mais recente recebido para o modelo. |
| `synced_at` | Última sincronização deste modelo. |

O código externo é indexado **dentro da marca** por decisão de modelagem; a amostra não prova sua unicidade global. A chave única adicional `(brand_id, id)` permite validar a associação de marca nas tabelas por período. A resposta também contém um array `Anos`, documentado em `model_list_responses`.

### `model_list_responses` — resposta de modelos e anos por marca/período

Registro do objeto retornado por `ConsultarModelos` para uma marca num período. **Fonte:** objeto integral com arrays `Modelos` e `Anos`. Este é um armazenamento da resposta observada, não uma associação de cada ano a cada modelo.

| Coluna | Descrição |
| --- | --- |
| `period_id` | Período enviado na requisição. |
| `brand_id` | Marca enviada na requisição. |
| `raw_response` | JSON integral, incluindo `Modelos` e `Anos`. |
| `synced_at` | Última sincronização desta lista. |

Chave primária `(period_id, brand_id)`, referenciando uma marca listada nesse período. Uma nova consulta pode substituir o JSON anterior; não há histórico de versões da mesma resposta.

### `model_variants` — anos-modelo e combustíveis

Uma variante de um modelo, definida por ano-modelo e combustível. **Fonte:** `ConsultarAnoModelo` (`List Ano - Modelo.yml`).

| Coluna | Descrição |
| --- | --- |
| `id` | Identificador interno da variante. |
| `model_id` | Modelo ao qual a variante pertence (`models.id`). |
| `source_value` | `Value` original da opção, como `"2023-5"` ou `"32000-5"`. |
| `model_year` | Parte numérica inicial de `Value`, enviada como `anoModelo`. |
| `fuel_code` | Parte após o hífen em `Value`, enviada como `codigoTipoCombustivel`; conservada como texto. |
| `synced_at` | Última sincronização desta variante. |

As chaves `(model_id, source_value)` e `(model_id, model_year, fuel_code)` são únicas. A chave `(model_id, id)` serve para validar a associação com o modelo por período. Nenhum significado especial é atribuído ao valor `32000` pelo esquema.

### `period_brands` — marcas listadas em um período

Disponibilidade de marcas por mês. **Fonte:** resposta de `ConsultarMarcas` para o período e tipo correspondentes.

| Coluna | Descrição |
| --- | --- |
| `period_id` | Período consultado (`reference_periods.id`). |
| `brand_id` | Marca listada (`brands.id`). |
| `display_name` | `Label` da marca na lista daquele período. |
| `synced_at` | Última sincronização desta associação. |

Chave primária `(period_id, brand_id)`.

### `brand_list_responses` — lista de marcas por período/tipo

**Fonte:** resposta integral de `ConsultarMarcas` para o par período/tipo. Guarda inclusive uma lista vazia para diferenciar "consulta feita sem resultados" de "ainda não consultado".

| Coluna | Descrição |
| --- | --- |
| `period_id` | Período da consulta. |
| `vehicle_type` | Tipo enviado como `codigoTipoVeiculo`. |
| `raw_response` | Array JSON integral de `Label`/`Value`. |
| `synced_at` | Última sincronização da lista. |

Chave primária `(period_id, vehicle_type)`.

### `period_models` — modelos listados em um período

Disponibilidade de modelos por mês e marca. **Fonte:** resposta de `ConsultarModelos` para o período, tipo e marca correspondentes.

| Coluna | Descrição |
| --- | --- |
| `period_id` | Período consultado. |
| `brand_id` | Marca listada no período (`period_brands`). |
| `model_id` | Modelo listado (`models`). |
| `display_name` | `Modelos[].Label` na lista daquele período. |
| `synced_at` | Última sincronização desta associação. |

Chave primária `(period_id, model_id)`. As chaves estrangeiras compostas exigem que a marca esteja listada no período e que o modelo pertença a ela.

### `period_variants` — variantes listadas em um período

Disponibilidade de anos-modelo/combustíveis por mês e modelo. **Fonte:** resposta de `ConsultarAnoModelo` para o período e modelo correspondentes.

| Coluna | Descrição |
| --- | --- |
| `period_id` | Período consultado. |
| `model_id` | Modelo listado no período (`period_models`). |
| `variant_id` | Variante listada (`model_variants`). |
| `display_label` | `Label` da opção retornada por `ConsultarAnoModelo` naquele período. |
| `synced_at` | Última sincronização desta associação. |

Chave primária `(period_id, variant_id)`. As chaves estrangeiras compostas exigem que o modelo esteja listado no período e que a variante pertença a ele. Presença nesta tabela **não** significa que o preço já tenha sido coletado.

### `year_list_responses` — lista de anos/combustíveis por período/modelo

**Fonte:** resposta integral de `ConsultarAnoModelo` para o par período/modelo. Guarda inclusive listas vazias.

| Coluna | Descrição |
| --- | --- |
| `period_id` | Período da consulta. |
| `model_id` | Modelo listado no período. |
| `raw_response` | Array JSON integral das opções `Label`/`Value`. |
| `synced_at` | Última sincronização da lista. |

Chave primária `(period_id, model_id)`; chave estrangeira composta para `period_models`.

### `vehicle_prices` — valores consultados

Um valor para cada par período/variante efetivamente consultado. **Fonte:** `ConsultarValorComTodosParametros` (`List All Info.yml`).

| Coluna | Descrição |
| --- | --- |
| `period_id` | Período da cotação. |
| `variant_id` | Ano-modelo/combustível consultado. |
| `price_brl` | `Valor` convertido de texto monetário em reais para decimal com duas casas. |
| `fipe_code` | `CodigoFipe` textual, preservando zeros iniciais e hífen; não é chave da variante. |
| `raw_response` | Objeto JSON integral retornado pela consulta de valor, para conferência e rastreabilidade. |
| `synced_at` | Instante em que a cotação foi sincronizada. |

Chave primária `(period_id, variant_id)`; o par deve existir em `period_variants`. O preço deve ser positivo. Este esquema guarda **um valor atual por período/variante**: uma nova coleta do mesmo par pode substituí-lo. Se houver necessidade de preservar revisões do mesmo mês, será necessária uma tabela de revisões.

### `sync_runs` — tentativas de sincronização completa

Registro de tentativas de coleta **completa** de um tipo de veículo em um mês. **Fonte:** metadados produzidos pelo Fipely, não pela FIPE. Não registra consultas individuais de modelos ou variantes.

| Coluna | Descrição |
| --- | --- |
| `id` | Identificador da tentativa. |
| `period_id` | Período sincronizado. |
| `vehicle_type` | Tipo de veículo sincronizado (`1`, `2` ou `3`). |
| `status` | `running`, `completed` ou `failed`. |
| `started_at` | Início da tentativa. |
| `finished_at` | Término da tentativa; nulo apenas enquanto estiver em execução. |
| `error_message` | Detalhes de eventual erro, se disponíveis. |

`running`, `completed` e `failed` são **estados internos propostos para o Fipely**, não valores observados na FIPE. O banco verifica os valores possíveis e a coerência entre estado e horário de término. O significado operacional de `completed` está descrito em [fluxo.md](fluxo.md).

### `sync_jobs` — progresso de cada execução

**Fonte:** dados internos produzidos pelo `sinc-service`, não pela FIPE. Uma linha por sincronização de período, marca, modelo, variante e por tipo de veículo dentro de um período completo. `parent_job_id` relaciona os três tipos ao trabalho mensal principal. Criada na migração `V2__sync_progress.sql`.

| Coluna | Descrição |
| --- | --- |
| `id`, `parent_job_id` | ID da execução e, quando existir, da execução mensal pai. |
| `scope`, `reference_month`, `vehicle_type`, `brand_code`, `model_code`, `model_year`, `fuel_code`, `refresh_old_records`, `include_variants` | Escopo e parâmetros que identificam o trabalho. `include_variants` foi adicionado na migração V3. |
| `status`, `phase`, `current_brand`, `current_model` | Estado (`queued`, `running`, `completed`, `failed`) e atividade atual. |
| `brands_discovered`, `models_discovered`, `vehicles_discovered` | Quantidades identificadas até o momento; `vehicles_discovered` é **parcial** até o término da descoberta. |
| `vehicles_processed`, `vehicles_synced`, `vehicles_skipped` | Cotações concluídas, reconsultadas/inseridas e já atualizadas que foram aproveitadas. Uma cotação corresponde a uma variante em um período. |
| `discovery_complete` | Indica quando o total descoberto deixa de ser parcial. |
| `started_at`, `updated_at`, `finished_at`, `error_message` | Datas e eventual erro. |

O endpoint calcula `remainingKnown = vehicles_discovered - vehicles_processed`. Para o trabalho mensal pai, os totais são a soma dos três filhos. **Não** interpretar `remainingKnown = 0` como fim do trabalho enquanto `discovery_complete = false`.

## DDL (PostgreSQL)

O esquema abaixo usa tipos nativos do PostgreSQL e pode ser executado na ordem apresentada. Não pressupõe nenhuma extensão. Em instalações novas, prefira executar o serviço e deixar o Flyway aplicar a migração, em vez de executar os dois DDLs.

```sql
CREATE SCHEMA IF NOT EXISTS fipe;
SET search_path TO fipe;

CREATE TABLE reference_periods (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    external_code integer NOT NULL UNIQUE,
    source_month_label text NOT NULL,
    reference_month date NOT NULL UNIQUE,
    CONSTRAINT reference_month_first_day
        CHECK (EXTRACT(DAY FROM reference_month) = 1)
);

CREATE TABLE brands (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    vehicle_type smallint NOT NULL,
    external_code text NOT NULL,
    name text NOT NULL,
    synced_at timestamptz NOT NULL,
    CONSTRAINT brands_vehicle_type_valid CHECK (vehicle_type IN (1, 2, 3)),
    CONSTRAINT brands_type_code_unique UNIQUE (vehicle_type, external_code)
);

CREATE TABLE models (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    brand_id bigint NOT NULL REFERENCES brands (id),
    external_code integer NOT NULL,
    name text NOT NULL,
    synced_at timestamptz NOT NULL,
    CONSTRAINT models_brand_code_unique UNIQUE (brand_id, external_code),
    CONSTRAINT models_brand_id_unique UNIQUE (brand_id, id)
);

CREATE TABLE model_variants (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    model_id bigint NOT NULL REFERENCES models (id),
    source_value text NOT NULL,
    model_year integer NOT NULL,
    fuel_code text NOT NULL,
    synced_at timestamptz NOT NULL,
    CONSTRAINT model_variants_year_valid CHECK (model_year >= 0),
    CONSTRAINT model_variants_source_unique UNIQUE (model_id, source_value),
    CONSTRAINT model_variants_identity_unique
        UNIQUE (model_id, model_year, fuel_code),
    CONSTRAINT model_variants_model_id_unique UNIQUE (model_id, id)
);

CREATE TABLE period_brands (
    period_id bigint NOT NULL REFERENCES reference_periods (id),
    brand_id bigint NOT NULL REFERENCES brands (id),
    display_name text NOT NULL,
    synced_at timestamptz NOT NULL,
    PRIMARY KEY (period_id, brand_id)
);

CREATE TABLE brand_list_responses (
    period_id bigint NOT NULL REFERENCES reference_periods (id),
    vehicle_type smallint NOT NULL CHECK (vehicle_type IN (1, 2, 3)),
    raw_response jsonb NOT NULL CHECK (jsonb_typeof(raw_response) = 'array'),
    synced_at timestamptz NOT NULL,
    PRIMARY KEY (period_id, vehicle_type)
);

CREATE TABLE model_list_responses (
    period_id bigint NOT NULL,
    brand_id bigint NOT NULL,
    raw_response jsonb NOT NULL,
    synced_at timestamptz NOT NULL,
    PRIMARY KEY (period_id, brand_id),
    CONSTRAINT model_list_responses_object
        CHECK (jsonb_typeof(raw_response) = 'object'),
    FOREIGN KEY (period_id, brand_id)
        REFERENCES period_brands (period_id, brand_id)
);

CREATE TABLE period_models (
    period_id bigint NOT NULL,
    brand_id bigint NOT NULL,
    model_id bigint NOT NULL,
    display_name text NOT NULL,
    synced_at timestamptz NOT NULL,
    PRIMARY KEY (period_id, model_id),
    FOREIGN KEY (period_id, brand_id)
        REFERENCES period_brands (period_id, brand_id),
    FOREIGN KEY (brand_id, model_id)
        REFERENCES models (brand_id, id)
);

CREATE TABLE year_list_responses (
    period_id bigint NOT NULL,
    model_id bigint NOT NULL,
    raw_response jsonb NOT NULL CHECK (jsonb_typeof(raw_response) = 'array'),
    synced_at timestamptz NOT NULL,
    PRIMARY KEY (period_id, model_id),
    FOREIGN KEY (period_id, model_id)
        REFERENCES period_models (period_id, model_id)
);

CREATE TABLE period_variants (
    period_id bigint NOT NULL,
    model_id bigint NOT NULL,
    variant_id bigint NOT NULL,
    display_label text NOT NULL,
    synced_at timestamptz NOT NULL,
    PRIMARY KEY (period_id, variant_id),
    FOREIGN KEY (period_id, model_id)
        REFERENCES period_models (period_id, model_id),
    FOREIGN KEY (model_id, variant_id)
        REFERENCES model_variants (model_id, id)
);

CREATE TABLE vehicle_prices (
    period_id bigint NOT NULL,
    variant_id bigint NOT NULL,
    price_brl numeric(14, 2) NOT NULL,
    fipe_code text NOT NULL,
    raw_response jsonb NOT NULL,
    synced_at timestamptz NOT NULL,
    PRIMARY KEY (period_id, variant_id),
    CONSTRAINT vehicle_prices_positive CHECK (price_brl > 0),
    CONSTRAINT vehicle_prices_response_object
        CHECK (jsonb_typeof(raw_response) = 'object'),
    FOREIGN KEY (period_id, variant_id)
        REFERENCES period_variants (period_id, variant_id)
);

CREATE TABLE sync_runs (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    period_id bigint NOT NULL REFERENCES reference_periods (id),
    vehicle_type smallint NOT NULL,
    status text NOT NULL,
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    error_message text,
    CONSTRAINT sync_runs_vehicle_type_valid CHECK (vehicle_type IN (1, 2, 3)),
    CONSTRAINT sync_runs_status_valid
        CHECK (status IN ('running', 'completed', 'failed')),
    CONSTRAINT sync_runs_finished_status
        CHECK ((status = 'running' AND finished_at IS NULL)
            OR (status IN ('completed', 'failed') AND finished_at IS NOT NULL))
);

-- A PK das tabelas por período já indexa consultas iniciadas por period_id.
-- Estes índices atendem consultas de histórico e acompanhamento das coletas.
CREATE INDEX vehicle_prices_variant_period_idx
    ON vehicle_prices (variant_id, period_id);

CREATE INDEX period_variants_variant_period_idx
    ON period_variants (variant_id, period_id);

CREATE INDEX sync_runs_period_type_started_idx
    ON sync_runs (period_id, vehicle_type, started_at DESC);
```

Migração V2 para o progresso (execute-a pelo Flyway, não manualmente sobre uma instalação já migrada):

```sql
CREATE TABLE fipe.sync_jobs (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    parent_job_id bigint REFERENCES fipe.sync_jobs (id),
    scope text NOT NULL CHECK (scope IN ('period', 'vehicleType', 'brand', 'model', 'variant')),
    reference_month date NOT NULL,
    vehicle_type smallint CHECK (vehicle_type IN (1, 2, 3)),
    brand_code text,
    model_code integer,
    model_year integer,
    fuel_code text,
    refresh_old_records boolean NOT NULL,
    status text NOT NULL CHECK (status IN ('queued', 'running', 'completed', 'failed')),
    phase text NOT NULL,
    current_brand text,
    current_model text,
    brands_discovered bigint NOT NULL DEFAULT 0 CHECK (brands_discovered >= 0),
    models_discovered bigint NOT NULL DEFAULT 0 CHECK (models_discovered >= 0),
    vehicles_discovered bigint NOT NULL DEFAULT 0 CHECK (vehicles_discovered >= 0),
    vehicles_processed bigint NOT NULL DEFAULT 0 CHECK (vehicles_processed >= 0),
    vehicles_synced bigint NOT NULL DEFAULT 0 CHECK (vehicles_synced >= 0),
    vehicles_skipped bigint NOT NULL DEFAULT 0 CHECK (vehicles_skipped >= 0),
    discovery_complete boolean NOT NULL DEFAULT false,
    started_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    error_message text,
    CHECK (vehicles_processed <= vehicles_discovered),
    CHECK (vehicles_synced + vehicles_skipped = vehicles_processed),
    CHECK ((status IN ('queued', 'running') AND finished_at IS NULL)
        OR (status IN ('completed', 'failed') AND finished_at IS NOT NULL))
);

CREATE INDEX sync_jobs_status_updated_idx ON fipe.sync_jobs (status, updated_at DESC);
CREATE INDEX sync_jobs_parent_idx ON fipe.sync_jobs (parent_job_id);
```

Migração V3, necessária após a V2 para diferenciar a carga de catálogo dos preços:

```sql
ALTER TABLE fipe.sync_jobs DROP CONSTRAINT sync_jobs_scope_check;
ALTER TABLE fipe.sync_jobs ADD CONSTRAINT sync_jobs_scope_check
    CHECK (scope IN ('period', 'vehicleType', 'brand', 'model', 'variant', 'catalog', 'catalogType'));
ALTER TABLE fipe.sync_jobs ADD COLUMN include_variants boolean NOT NULL DEFAULT false;
```

## Limites das amostras e hipóteses do esquema

As respostas efetivamente consultadas confirmam os formatos **dos exemplos acima**, não garantem que todas as respostas históricas usem os mesmos campos ou que não existam resultados vazios/erros. As chaves únicas por mês, marca, modelo e variante, o preço positivo e o registro de uma cotação por período/variante são **decisões de modelagem**, não propriedades comprovadas pela amostra. Antes de uma importação em massa, validar os formatos nas demais tabelas de referência e estabelecer o tratamento para respostas que não satisfaçam as restrições. A migração V3 é aplicada pelo Flyway ao iniciar o serviço atualizado.

## Limpar os dados para recomeçar

**Atenção:** o comando abaixo apaga **todos os registros sincronizados, tentativas e histórico de progresso**. Execute-o somente conectado ao banco **`fipely`**, com o serviço de sincronização parado. Ele mantém as tabelas, índices, constraints e `fipe.flyway_schema_history`; não desfaz migrações. `RESTART IDENTITY` reinicia os IDs gerados automaticamente. Uma falha antes do `COMMIT` permite desfazer a transação com `ROLLBACK`.

```sql
BEGIN;

DO $$
BEGIN
    IF current_database() <> 'fipely' THEN
        RAISE EXCEPTION 'Conecte-se ao banco fipely antes de executar o reset';
    END IF;
END
$$;

TRUNCATE TABLE
    fipe.sync_jobs,
    fipe.vehicle_prices,
    fipe.period_variants,
    fipe.year_list_responses,
    fipe.period_models,
    fipe.model_list_responses,
    fipe.brand_list_responses,
    fipe.period_brands,
    fipe.model_variants,
    fipe.models,
    fipe.brands,
    fipe.sync_runs,
    fipe.reference_periods
RESTART IDENTITY;

COMMIT;
```
