# AXS — Axie Infinity

| | |
|---|---|
| Инструмент Kraken | `PF_AXSUSD` (перп, USD) |
| CoinGecko id | axie-infinity |
| Капитализация | $159.1 млн |
| Цена | $0.912864 |
| Циркулирующее предложение | 174.43 млн |
| Всего / максимум | 270 млн / 270 млн |
| Теги | Gaming, Ethereum (ETH) Token (ERC-20), NFT Token, Harmony (ONE) Token, Binance Coin (BNB) Token (BEP-20), Play to Earn (P2E) |

## Что за монета

Axie Infinity — токен управления игровой экосистемы, 2020.

## Разлоки

**Статус: НАЙДЕНЫ.** Источник — DefiLlama emissions (бесплатно, без ключа).

- Протокол DefiLlama: `axie-infinity` («Axie Infinity»)
- Категория / сеть: Gaming / 
- Событий в расписании: **29** (прошедших 29, будущих cliff-событий 0)
- Максимальное предложение по расписанию: 270 млн

### Аллокация (доля финального предложения, %)

| Транш | сейчас | финал | разлочено |
|---|---|---|---|
| Public Sale | 11 | 11 | 100% |
| Private Sale | 4 | 4 | 100% |
| Advisors | 7 | 7 | 100% |
| Ecosystem Fund | 8 | 8 | 100% |
| Play to Earn | 18.3 | 18.3 | 100% |
| Sky Mavis | 21 | 21 | 100% |
| Staking Reward | 30.8 | 30.8 | 100% |

### Ближайшие разлоки (cliff)

Дискретных (cliff) событий впереди нет — для S5, который торгует именно обрывы, монета сейчас событий не даёт.
Это НЕ значит, что предложение не растёт: см. непрерывную эмиссию ниже.

### Непрерывная эмиссия (весь ряд `unlocked`)

Суммарный прирост разлоченного предложения по посуточному ряду — включает `linear`-транши, стейкинг-награды и майнинг, а не только обрывы:

| Горизонт | Прирост | % циркуляции |
|---|---|---|
| +30 дней | 0 | 0% |
| +90 дней | 0 | 0% |
| до конца расписания | 0 | 0% |

⚠️ Для монет с майнинговой или стейкинговой эмиссией эта таблица показывает инфляцию, а не разлоки. Событие S5 — только строка из таблицы cliff выше.

## Автосбор

**Да, полностью автоматизируемо.**

```
GET https://defillama-datasets.llama.fi/emissions/axie-infinity
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
