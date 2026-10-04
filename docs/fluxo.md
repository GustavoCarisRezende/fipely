# Fluxos do Fipely

Os exemplos de requisições estão em `request-collection/fipe-org/Example/`. As respostas dos cinco endpoints foram consultadas para julho/2026, com exemplos de carro, moto e caminhão; seus formatos observados e a estrutura das tabelas estão em [database.md](database.md). Esta página descreve o fluxo implementado em `sinc-service` para percorrer a FIPE, persistir resultados e consultar o histórico.

## Carga inicial de catálogo (sem preços)

`POST /sync/catalog` usa por padrão o período mais recente informado pela FIPE, os tipos `1`, `2`, `3` e `includeVariants=false`. Também aceita `referenceMonth` e `vehicleType` no corpo para restringir a consulta. O período mais recente vem da lista da FIPE, cujo resultado é mantido em cache de memória pelo tempo configurado; não se infere o código a partir do mês nem se assume que a ordem da resposta identifica o mais recente.

Para cada tipo, listar marcas e modelos; salvar `brands`, `models`, `period_brands`, `period_models` e as respostas completas das listas. Se `includeVariants=true`, consultar `ConsultarAnoModelo` para cada modelo e persistir `model_variants`, `period_variants` e as listas de anos. **Não consultar preços** e **não** marcar `sync_runs` como concluído. A resposta `Anos` em `ConsultarModelos` é da marca e não autoriza associar cada ano a todos os modelos. A leitura do catálogo persistido fica a cargo da aplicação principal.

Antes de enviar cada requisição à FIPE, o serviço verifica a lista persistida, inclusive listas vazias. Com `refreshOldRecords=false`, reutiliza as listas existentes; com `true`, reconsulta somente as que atingiram a idade mínima configurada. Assim uma segunda carga do mesmo período, tipo e nível de detalhe não repete as consultas de marcas/modelos/anos. A carga inicial pelo período mais recente é uma estratégia de cobertura ampla; não comprova a disponibilidade histórica em meses ainda não consultados.

Uma sincronização de preços para o **mesmo período** reutiliza as listas de marcas e modelos cadastradas pela carga de catálogo; se `includeVariants=true`, reutiliza também as listas de variantes. Com `includeVariants=false`, precisa consultar os anos/combustíveis por modelo antes dos preços. Cada lista tem chave que inclui o período e só é reutilizada nesse período. Não se presume que marcas, modelos ou variantes estejam disponíveis em meses anteriores apenas por existirem no catálogo atual.

## Sincronização de um período

Exemplo: solicitação de sincronização de **07/2026**.

1. Consultar `ConsultarTabelaDeReferencia` (`List Períodos.yml`) e localizar o mês solicitado. Na resposta consultada, `"Codigo": 335` correspondeu a `"Mes": "julho/2026 "`. Persistir a string original em `source_month_label`, o mês normalizado como `2026-07-01` e o código `335` como `external_code`. O código da tabela não deve ser inferido a partir da data.
2. Para cada tipo de veículo (`1` = carros, `2` = motos, `3` = caminhões), iniciar uma tentativa de sincronização completa em `sync_runs`.
3. Consultar `ConsultarMarcas` (`List Marcas.yml`) com `codigoTabelaReferencia` e `codigoTipoVeiculo`; salvar a resposta em `brand_list_responses`, cada marca em `brands` e sua disponibilidade em `period_brands`.
4. Para cada marca, consultar `ConsultarModelos` (`List Modelos.yml`) com período, tipo e `codigoMarca`; salvar o objeto integral (`Modelos` e `Anos`) em `model_list_responses` e cada item de `Modelos` em `models` e `period_models`. **Não** associar todos os itens de `Anos` a todos os modelos: a resposta é por marca, não por modelo.
5. Para cada modelo, consultar `ConsultarAnoModelo` (`List Ano - Modelo.yml`) com período, tipo, marca e `codigoModelo`; salvar o retorno em `year_list_responses`, o `Value` original (por exemplo, `"2023-5"`) em `model_variants.source_value`, separar suas partes para os parâmetros `anoModelo` e `codigoTipoCombustivel` e salvar o `Label` em `period_variants.display_label`. Na amostra também houve `"32000-5"`, sem indicação textual do significado de `32000`.
6. Para cada variante, consultar `ConsultarValorComTodosParametros` (`List All Info.yml`) com período, marca, modelo, tipo, `anoModelo`, `codigoTipoCombustivel`, `tipoVeiculo` e `tipoConsulta=tradicional`; salvar o preço e a resposta original em `vehicle_prices`.
7. Marcar a tentativa daquele tipo como `completed` somente depois que todas as marcas, modelos, variantes e respectivos preços tiverem sido consultados com sucesso. Em caso de interrupção/erro, marcá-la como `failed`, conservando os dados parciais para retomada.

Em paralelo, cada requisição `POST /sync/...` cria uma execução em `sync_jobs`. A sincronização mensal também cria três execuções filhas (uma por tipo). Eventos WebSocket e consultas `GET /sync/jobs` mostram etapa atual, marca/modelo, contadores de cotações descobertas e concluídas, e erros. Uma cotação já atualizada conta como `vehicles_skipped`, não como nova consulta FIPE. `remainingKnown` pode aumentar quando novos modelos são descobertos; só representa o restante exato quando `discoveryComplete=true`. Conexões posteriores recebem uma lista inicial de execuções ativas. Após reiniciar o serviço, execuções interrompidas passam a `failed` e mantêm os contadores registrados.

`period_brands`, `period_models` e `period_variants` indicam que uma opção foi **listada**; `vehicle_prices` indica que seu **valor foi consultado**. A ausência de um registro de preço não prova que a FIPE não tinha valor: a coleta pode estar incompleta. Da mesma forma, a ausência de uma opção nas tabelas `period_*` não prova que ela não existia na FIPE até que a sincronização completa do tipo termine. `model_list_responses` preserva inclusive o array `Anos` devolvido junto aos modelos.

Usar as chaves únicas do banco para tornar inserções e reconsultas idempotentes (`INSERT ... ON CONFLICT`). Cada registro FIPE sincronizado, exceto o período, tem `synced_at`; uma reconsulta atualiza esse instante. Com `refreshOldRecords=false`, consultar somente listas/cotações ainda não persistidas. Com `refreshOldRecords=true`, reconsultar também registros cuja última sincronização tenha pelo menos `app.sync.min-age-days` dias (365 por padrão). Respeitar a taxa global e os retries configurados em `application.properties`. Converter valores monetários textuais para decimal antes da gravação, sem usar ponto flutuante. Consultas pontuais de modelos/variantes podem popular as mesmas tabelas, mas **não** devem marcar a sincronização completa como concluída.

## Histórico de um veículo

Aqui, “todos os modelos” significa **todos os anos-modelo/combustíveis de um modelo**. Assim, há dois níveis de consulta:

- **Modelo inteiro:** localizar `models.id` e consultar os preços de todas as suas `model_variants` ao longo dos períodos.
- **Variante individual:** localizar `model_variants.id` e consultar apenas o histórico daquele ano-modelo/combustível.

Exemplo de consulta do histórico de um modelo inteiro, retornando somente cotações já coletadas:

```sql
SELECT rp.reference_month,
       mv.model_year,
       mv.fuel_code,
       pv.display_label AS year_fuel_label,
       vp.fipe_code,
       vp.price_brl,
       vp.synced_at
FROM model_variants AS mv
JOIN vehicle_prices AS vp ON vp.variant_id = mv.id
JOIN reference_periods AS rp ON rp.id = vp.period_id
JOIN period_variants AS pv
  ON pv.period_id = vp.period_id AND pv.variant_id = vp.variant_id
WHERE mv.model_id = $1
ORDER BY rp.reference_month, mv.model_year, mv.fuel_code;
```

Para o histórico de um único ano/combustível, substituir `WHERE mv.model_id = $1` por `WHERE mv.id = $1`. Para exibir opções listadas em um período cujo preço ainda não foi coletado, consultar `period_variants` com `LEFT JOIN vehicle_prices`.

## Relatórios de períodos

- `/reports/periods/absent`: compara os períodos retornados pela FIPE com `fipe.reference_periods` e devolve os meses que não existem localmente.
- `/reports/periods/incomplete`: para cada período local, informa os tipos (`1`, `2`, `3`) sem uma tentativa `completed` em `fipe.sync_runs`. Consultas pontuais não completam um tipo.

As rotas HTTP completas e os exemplos de entrada estão em `sinc-service/README.md`.

## Limite da verificação

As respostas executadas confirmam os formatos dos exemplos documentados, não os de todas as marcas, modelos e referências históricas. Antes de importar o acervo inteiro, tratar explicitamente respostas vazias/erros e possíveis diferenças no formato `ano-combustível`, nos campos da cotação ou no preço. Não classificar `32000` como zero km apenas com base nas respostas observadas.
