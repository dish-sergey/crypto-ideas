# BB — BB

| | |
|---|---|
| Инструмент Kraken | `PF_BBUSD` (перп, USD) |
| CoinGecko id | — |
| Капитализация | н/д |
| Цена | н/д |
| Циркулирующее предложение | 0 |
| Всего / максимум | 0 / не ограничен |
| Теги | Proof Of Stake, Solana (SOL) Token, Ethereum (ETH) Token (ERC-20) |

## Что за монета

BounceBit — CeDeFi-рестейкинг биткоина. TGE май 2024.

## Разлоки

**Статус: НАЙДЕНЫ.** Источник — DefiLlama emissions (бесплатно, без ключа).

- Протокол DefiLlama: `bouncebit` («BounceBit»)
- Категория / сеть: Yield / 
- Событий в расписании: **123** (прошедших 73, будущих cliff-событий 50)
- Максимальное предложение по расписанию: 2.1 млрд

### Аллокация (доля финального предложения, %)

| Транш | сейчас | финал | разлочено |
|---|---|---|---|
| Testnet & TVL Incentives | 6.7 | 4 | 100% |
| Binance Megadrop | 13.4 | 8 | 100% |
| Market Making | 5 | 3 | 100% |
| Investors | 28.2 | 21 | 80% |
| Bounce Club & Ecosystem Reserve | 22.4 | 14 | 95.2% |
| Team | 6.7 | 10 | 40% |
| Advisors | 3.4 | 5 | 40% |
| Staking Reward & Delegation Program | 14.1 | 35 | 23.9% |

### Ближайшие разлоки (cliff)

| Дата | Токенов | % циркуляции | ≈ USD | Транш |
|---|---|---|---|---|
| 2026-09-11 | 36.99 млн | н/д | н/д | Uncategorized, insiders, privateSale |
| 2026-10-12 | 29.93 млн | н/д | н/д | insiders, privateSale |
| 2026-11-11 | 29.93 млн | н/д | н/д | insiders, privateSale |
| 2026-12-11 | 7.88 млн | н/д | н/д | insiders |
| 2027-01-11 | 7.88 млн | н/д | н/д | insiders |
| 2027-02-10 | 7.88 млн | н/д | н/д | insiders |
| 2027-03-13 | 7.88 млн | н/д | н/д | insiders |
| 2027-04-12 | 7.88 млн | н/д | н/д | insiders |
| 2027-05-13 | 7.88 млн | н/д | н/д | insiders |
| 2027-06-12 | 7.88 млн | н/д | н/д | insiders |

**Давление на горизонте S5:** 30 дней — 36.99 млн токенов (н/д циркуляции, н/д; 90 дней — 96.85 млн, н/д).

### Непрерывная эмиссия (весь ряд `unlocked`)

Суммарный прирост разлоченного предложения по посуточному ряду — включает `linear`-транши, стейкинг-награды и майнинг, а не только обрывы:

| Горизонт | Прирост | % циркуляции |
|---|---|---|
| +30 дней | 43.03 млн | н/д |
| +90 дней | 122.03 млн | н/д |
| до конца расписания | 850.43 млн | н/д |

⚠️ Для монет с майнинговой или стейкинговой эмиссией эта таблица показывает инфляцию, а не разлоки. Событие S5 — только строка из таблицы cliff выше.

## Автосбор

**Да, полностью автоматизируемо.**

```
GET https://defillama-datasets.llama.fi/emissions/bouncebit
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
