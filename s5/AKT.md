# AKT — Akash Network

| | |
|---|---|
| Инструмент Kraken | `PF_AKTUSD` (перп, USD) |
| CoinGecko id | akash-network |
| Капитализация | $148.14 млн |
| Цена | $0.498041 |
| Циркулирующее предложение | 297.34 млн |
| Всего / максимум | 297.41 млн / 388.54 млн |
| Теги | Marketplace, Ethereum (ETH) Token (ERC-20), Polygon (MATIC) Token, AI (Artificial Intelligence), Distributed Computing, Web3 |

## Что за монета

Akash Network — децентрализованный маркетплейс вычислений (Cosmos SDK).

## Разлоки

**Статус: НЕ НАЙДЕНЫ (бесплатно). Настоящий пробел.**

Вестинг у токена ЕСТЬ (транши команды/инвесторов/экосистемы), но адаптер DefiLlama не написан — расписание бесплатно и машиночитаемо недоступно. Это настоящий пробел: кандидат на ручное ведение или платный источник.

В открытом корпусе DefiLlama (`emissionsProtocolsList`, 372 протокола) адаптера для AKT нет.

Где данные, скорее всего, есть — все платные:

- CryptoRank: `GET /v2/currencies/{id}/vesting` и `/vesting/chart` (тариф Pro).
- Tokenomist (бывш. TokenUnlocks) — подписка.
- DropsTab: `/tokenUnlocks`; бесплатно только по заявке в Builders Program (некоммерческое использование).

## Автосбор

**Нет.** Бесплатного машиночитаемого расписания для AKT не найдено.
Варианты: (1) вести расписание вручную по документации проекта, (2) написать ончейн-адаптер под контракт вестинга,
(3) подать адаптер в открытый репозиторий `DefiLlama/emissions-adapters` — тогда данные придут в CDN сами.

---

Сгенерировано 2026-09-02 из Kraken Futures `instruments`, DefiLlama emissions и CoinGecko.
