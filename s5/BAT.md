# BAT — Basic Attention

| | |
|---|---|
| Инструмент Kraken | `PF_BATUSD` (перп, USD) |
| CoinGecko id | basic-attention-token |
| Капитализация | $102.43 млн |
| Цена | $0.068575 |
| Циркулирующее предложение | 1.5 млрд |
| Всего / максимум | 1.5 млрд / 1.5 млрд |
| Теги | Platform, Smart Contracts, Ethereum (ETH) Token (ERC-20), Monetization, Media & Publishing, Commerce & Advertising |

## Что за монета

Basic Attention Token — рекламная экономика браузера Brave, ICO 2017.

## Разлоки

**Статус: НАЙДЕНЫ.** Источник — DefiLlama emissions (бесплатно, без ключа).

- Протокол DefiLlama: `basic-attention` («Basic Attention»)
- Категория / сеть: Services / 
- Событий в расписании: **3** (прошедших 3, будущих cliff-событий 0)
- Максимальное предложение по расписанию: 1.5 млрд

### Аллокация (доля финального предложения, %)

| Транш | сейчас | финал | разлочено |
|---|---|---|---|
| Investor | 66.7 | 66.7 | 100% |
| User Growth Pool | 20 | 20 | 100% |
| Brave | 13.3 | 13.3 | 100% |

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
GET https://defillama-datasets.llama.fi/emissions/basic-attention
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
