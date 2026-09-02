# ARB — Arbitrum

| | |
|---|---|
| Инструмент Kraken | `PF_ARBUSD` (перп, USD) |
| CoinGecko id | arbitrum |
| Капитализация | $770.3 млн |
| Цена | $0.11512 |
| Циркулирующее предложение | 6.68 млрд |
| Всего / максимум | 10 млрд / 10 млрд |
| Теги | Arbitrum Ecosystem, Ethereum (ETH) Token (ERC-20), Layer 2 (L2), High Transaction Speed (TPS) |

## Что за монета

Arbitrum — крупнейший optimistic-роллап Ethereum. TGE март 2023.

## Разлоки

**Статус: НАЙДЕНЫ.** Источник — DefiLlama emissions (бесплатно, без ключа).

- Протокол DefiLlama: `arbitrum-foundation` («Arbitrum Foundation»)
- Категория / сеть: Canonical Bridge / Arbitrum
- Событий в расписании: **111** (прошедших 99, будущих cliff-событий 12)
- Максимальное предложение по расписанию: 10 млрд

### Аллокация (доля финального предложения, %)

| Транш | сейчас | финал | разлочено |
|---|---|---|---|
| Airdrop | 16.8 | 15.1 | 100% |
| Daos in Arbitrum | 1.6 | 1.5 | 100% |
| Arbitrum DAO Treasury | 17.7 | 15.9 | 100% |
| Team, Contributors & Advisors | 33.2 | 35 | 85.4% |
| Investors | 21.6 | 22.8 | 85.4% |
| Foundation | 9.1 | 9.7 | 84.5% |

### Ближайшие разлоки (cliff)

| Дата | Токенов | % циркуляции | ≈ USD | Транш |
|---|---|---|---|---|
| 2026-09-15 | 92.65 млн | 1.39% | $10.67 млн | insiders, privateSale |
| 2026-10-15 | 92.65 млн | 1.39% | $10.67 млн | insiders, privateSale |
| 2026-11-15 | 92.65 млн | 1.39% | $10.67 млн | insiders, privateSale |
| 2026-12-15 | 92.65 млн | 1.39% | $10.67 млн | insiders, privateSale |
| 2027-01-14 | 92.65 млн | 1.39% | $10.67 млн | insiders, privateSale |
| 2027-02-14 | 92.65 млн | 1.39% | $10.67 млн | insiders, privateSale |

**Давление на горизонте S5:** 30 дней — 92.65 млн токенов (1.39% циркуляции, $10.67 млн; 90 дней — 277.94 млн, 4.16%).

### Непрерывная эмиссия (весь ряд `unlocked`)

Суммарный прирост разлоченного предложения по посуточному ряду — включает `linear`-транши, стейкинг-награды и майнинг, а не только обрывы:

| Горизонт | Прирост | % циркуляции |
|---|---|---|
| +30 дней | 108.05 млн | 1.62% |
| +90 дней | 324.14 млн | 4.85% |
| до конца расписания | 764.54 млн | 11.45% |

⚠️ Для монет с майнинговой или стейкинговой эмиссией эта таблица показывает инфляцию, а не разлоки. Событие S5 — только строка из таблицы cliff выше.

## Автосбор

**Да, полностью автоматизируемо.**

```
GET https://defillama-datasets.llama.fi/emissions/arbitrum-foundation
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
