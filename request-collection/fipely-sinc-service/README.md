# Testes locais no Bruno

1. Abra esta pasta como coleção no Bruno e confirme que a environment **Local** está selecionada (uma seleção anterior de "No environment" pode sobrepor o padrão). A variável da URL é `baseurl`, em minúsculas, como o Bruno normaliza o hostname.
2. Inicie o serviço em `localhost:8080` com `FIPELY_API_TOKEN` configurado.
3. Edite a variável **secreta** `apiToken` na environment Local e informe **o mesmo token** do serviço. O valor não foi salvo na coleção.
4. Comece por **Sincronização → Sincronizar variante** (amostra de 07/2026), depois teste histórico e relatórios. A sincronização de uma marca ou de um período completo pode fazer muitas chamadas à FIPE e demorar bastante.

As outras variáveis de Local são exemplos efetivamente consultados na FIPE: moto (`vehicleType=2`), marca HONDA (`brandCode=80`), modelo CB 300F Twister Flex (`modelCode=10378`), ano `2023` e combustível `5`. Altere-as para outros veículos. `refreshOldRecords=false` por padrão; em `true`, registros existentes só são reconsultados quando atingirem a idade mínima configurada no serviço (365 dias por padrão). Os GETs apenas leem dados ou produzem relatórios, sem re-sincronizar registros.
