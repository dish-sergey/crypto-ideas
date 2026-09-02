# CYBER — CYBER

| | |
|---|---|
| Инструмент Kraken | `PF_CYBERUSD` (перп, USD) |
| CoinGecko id | cyberconnect |
| Капитализация | $18.17 млн |
| Цена | $0.297397 |
| Циркулирующее предложение | 61.07 млн |
| Всего / максимум | 100 млн / 100 млн |
| Теги | Ethereum (ETH) Token (ERC-20), Binance Coin (BNB) Token (BEP-20), Optimism Ecosystem, NFT Token, Layer 2 (L2), Multicoin Capital Portfolio |

## Что за монета

CyberConnect — децентрализованный социальный граф. TGE 2023.

## Разлоки

**Статус: НАЙДЕНЫ.** Источник — DefiLlama emissions (бесплатно, без ключа).

- Протокол DefiLlama: `cyberconnect` («CyberConnect»)
- Категория / сеть: SoFi / 
- Событий в расписании: **270** (прошедших 218, будущих cliff-событий 52)
- Максимальное предложение по расписанию: 100 млн
- Не распределено (TBD): 6.29 млн

### Аллокация (доля финального предложения, %)

| Транш | сейчас | финал | разлочено |
|---|---|---|---|
| Coinlist Public Sale | 3.9 | 3 | 100% |
| Private Sale | 21.7 | 25.1 | 66.7% |
| Team & Advisors | 11.3 | 15 | 58.3% |
| Community Rewards | 15.5 | 12 | 100% |
| Early Integration Partners | 6.5 | 5 | 100% |
| Marketing | 12.9 | 10 | 100% |
| Ecosystem Partners | 9.3 | 9 | 80% |
| Developer Community | 9.9 | 10 | 76.3% |
| Community Treasury | 9 | 10.9 | 64% |

### Ближайшие разлоки (cliff)

| Дата | Токенов | % циркуляции | ≈ USD | Транш |
|---|---|---|---|---|
| 2026-09-14 | 511.1 тыс | 0.84% | $152 тыс | insiders, noncirculating |
| 2026-10-14 | 511.1 тыс | 0.84% | $152 тыс | insiders, noncirculating |
| 2026-11-14 | 1.76 млн | 2.88% | $523.8 тыс | Uncategorized, insiders, noncirculating |
| 2026-11-14 | 2.09 млн | 3.43% | $622.6 тыс | privateSale |
| 2026-12-14 | 511.1 тыс | 0.84% | $152 тыс | insiders, noncirculating |
| 2027-01-13 | 511.1 тыс | 0.84% | $152 тыс | insiders, noncirculating |
| 2027-02-13 | 1.76 млн | 2.88% | $523.8 тыс | Uncategorized, insiders, noncirculating |
| 2027-02-13 | 2.09 млн | 3.43% | $622.6 тыс | privateSale |
| 2027-03-15 | 511.1 тыс | 0.84% | $152 тыс | insiders, noncirculating |
| 2027-04-15 | 511.1 тыс | 0.84% | $152 тыс | insiders, noncirculating |

**Давление на горизонте S5:** 30 дней — 511.1 тыс токенов (0.84% циркуляции, $152 тыс; 90 дней — 4.88 млн, 7.98%).

### Непрерывная эмиссия (весь ряд `unlocked`)

Суммарный прирост разлоченного предложения по посуточному ряду — включает `linear`-транши, стейкинг-награды и майнинг, а не только обрывы:

| Горизонт | Прирост | % циркуляции |
|---|---|---|
| +30 дней | 511.1 тыс | 0.84% |
| +90 дней | 4.88 млн | 7.98% |
| до конца расписания | 22.72 млн | 37.19% |

⚠️ Для монет с майнинговой или стейкинговой эмиссией эта таблица показывает инфляцию, а не разлоки. Событие S5 — только строка из таблицы cliff выше.

## Автосбор

**Да, полностью автоматизируемо.**

```
GET https://defillama-datasets.llama.fi/emissions/cyberconnect
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
