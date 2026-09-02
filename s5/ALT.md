# ALT — AltLayer

| | |
|---|---|
| Инструмент Kraken | `PF_ALTUSD` (перп, USD) |
| CoinGecko id | altlayer |
| Капитализация | $43.27 млн |
| Цена | $0.00606805 |
| Циркулирующее предложение | 7.13 млрд |
| Всего / максимум | 10 млрд / 10 млрд |
| Теги | Binance Coin (BNB) Token (BEP-20), Ethereum (ETH) Token (ERC-20), Binance Launchpool |

## Что за монета

AltLayer — рестейкнутые роллапы (rollups-as-a-service). TGE январь 2024.

## Разлоки

**Статус: НАЙДЕНЫ.** Источник — DefiLlama emissions (бесплатно, без ключа).

- Протокол DefiLlama: `altlayer` («AltLayer»)
- Категория / сеть: Developer Tools / 
- Событий в расписании: **216** (прошедших 149, будущих cliff-событий 67)
- Максимальное предложение по расписанию: 10 млрд
- Не распределено (TBD): 836 млн

### Аллокация (доля финального предложения, %)

| Транш | сейчас | финал | разлочено |
|---|---|---|---|
| Binance Launchpool | 7 | 5 | 100% |
| Airdrop | 4.2 | 3 | 100% |
| Investors | 22.4 | 18.5 | 86.1% |
| Advisors | 5.9 | 5 | 83.3% |
| Team | 12.9 | 15 | 61.1% |
| Ecosystem & Community | 10.3 | 12 | 61.1% |
| Treasury | 18.5 | 21.5 | 61.1% |
| Protocol Development | 18.8 | 20 | 67% |

### Ближайшие разлоки (cliff)

| Дата | Токенов | % циркуляции | ≈ USD | Транш |
|---|---|---|---|---|
| 2026-09-25 | 240.1 млн | 3.37% | $1.46 млн | ecosystem, insiders, noncirculating, privateSale |
| 2026-10-26 | 240.1 млн | 3.37% | $1.46 млн | ecosystem, insiders, noncirculating, privateSale |
| 2026-11-25 | 240.1 млн | 3.37% | $1.46 млн | ecosystem, insiders, noncirculating, privateSale |
| 2026-12-26 | 175.85 млн | 2.47% | $1.07 млн | ecosystem, insiders, noncirculating |
| 2027-01-25 | 159.17 млн | 2.23% | $965.8 тыс | ecosystem, insiders, noncirculating |
| 2027-02-25 | 159.17 млн | 2.23% | $965.8 тыс | ecosystem, insiders, noncirculating |
| 2027-03-27 | 159.17 млн | 2.23% | $965.8 тыс | ecosystem, insiders, noncirculating |
| 2027-04-27 | 159.17 млн | 2.23% | $965.8 тыс | ecosystem, insiders, noncirculating |
| 2027-05-27 | 159.17 млн | 2.23% | $965.8 тыс | ecosystem, insiders, noncirculating |
| 2027-06-26 | 159.17 млн | 2.23% | $965.8 тыс | ecosystem, insiders, noncirculating |

**Давление на горизонте S5:** 30 дней — 240.1 млн токенов (3.37% циркуляции, $1.46 млн; 90 дней — 720.29 млн, 10.11%).

### Непрерывная эмиссия (весь ряд `unlocked`)

Суммарный прирост разлоченного предложения по посуточному ряду — включает `linear`-транши, стейкинг-награды и майнинг, а не только обрывы:

| Горизонт | Прирост | % циркуляции |
|---|---|---|
| +30 дней | 240.1 млн | 3.37% |
| +90 дней | 720.29 млн | 10.11% |
| до конца расписания | 2.89 млрд | 40.51% |

⚠️ Для монет с майнинговой или стейкинговой эмиссией эта таблица показывает инфляцию, а не разлоки. Событие S5 — только строка из таблицы cliff выше.

## Автосбор

**Да, полностью автоматизируемо.**

```
GET https://defillama-datasets.llama.fi/emissions/altlayer
```

Без ключа и без лимитов (CDN), JSON. Ключевые поля:

- `metadata.events[]` — дискретные события: `timestamp`, `noOfTokens`, `category`, `unlockType` (`cliff` / `linear`), `rateDurationDays`. Форвардные события присутствуют.
- `documentedData.data[]` — посуточный ряд `{timestamp, unlocked, rawEmission, burned}` по каждому траншу, включая будущее.
- `documentedData.tokenAllocation` — доли траншей (`current` / `final` / `progress`).
- `supplyMetrics` — `maxSupply`, `adjustedSupply`, `tbdAmount`.

⚠️ Платный `api.llama.fi/emissions` (HTTP 402) заменяется этим CDN — данные те же.
⚠️ У `unlockType: "linear"` поле `noOfTokens` хранит **смену скорости** `[было, стало]` за `rateDurationDays`, а не объём. Объём считать по `cliff` либо по разностям ряда `unlocked`.
⚠️ Часть `timestamp` приходит строкой, а не числом — приводить типы при разборе.

---

Сгенерировано 2026-09-02 из Kraken Futures `instruments`, DefiLlama emissions и CoinGecko.
