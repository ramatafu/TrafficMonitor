<img width="1536" height="1024" alt="изображение" src="https://github.com/user-attachments/assets/1f46cd73-0ce0-48a9-85c2-c87656f7d14e" />
# Traffic Monitor

> A local network traffic monitor and firewall for Android.
> Everything happens on the device: no servers, no analytics, no data leaves your phone.

**Status: maintenance mode.** Feature-complete and frozen; compatibility fixes for new
Android versions and regression fixes are welcome. Current release: `v0.1.0`.
License: GPLv3.

**Languages:** English (default) · [Русский](README.ru.md)

## What it is

Traffic Monitor raises a local VPN (`VpnService`), intercepts the device's IPv4 traffic
and shows, in real time: **which app → connects where → how many bytes**. Traffic is
forwarded onward (user-space NAT), so the internet keeps working while monitoring is
active. Beyond observation, it can block: an entire app, or individual domains
(e.g. telemetry), without breaking the app's main function.

No root required. No external packet-parsing libraries — IPv4/TCP/UDP headers are parsed
by hand.

## Features

- **Live connection list**: app → domain/IP:port, protocol, bytes ↑/↓, session count,
  last-seen time.
- **Per-packet app attribution** via UID (`ConnectivityManager.getConnectionOwnerUid`,
  API 29+).
- **Domains instead of raw IPs**: resolved from DNS answers and TLS SNI; IP↔domain cache;
  rows are re-aggregated when a domain becomes known later.
- **App blocking** (toggle): traffic is not forwarded; live sessions are torn down
  (TCP RST injected back into the tun, UDP mappings dropped); an "attempts" counter shows
  the app keeps knocking on the wall.
- **Domain blocking** (tap a row; the rule applies to all apps): DNS queries get NXDOMAIN
  from a local sinkhole; TLS connections are reset on SNI match.
- **Tracker hints**: heuristic list (including OEM firmware telemetry) plus name tokens
  (`analytics`, `telemetry`, …). The ⚠️ badge is a hint only — nothing is blocked
  automatically.
- **Footgun protection**: system apps and apps sharing a UID are flagged ⚠️ and require
  confirmation before blocking.
- **Persistence**: block rules and known domains are stored in Room and survive service
  restarts.
- **Localization**: English UI by default; a toggle in Settings switches the interface
  to Russian.

## Badges in the connection list

| Badge | Meaning |
|---|---|
| 🚫 | The app is blocked entirely (Apps tab). Traffic is not forwarded; the "attempts" counter grows. |
| ⛔ | The domain is blocked for all apps (tap the row to toggle). DNS → NXDOMAIN, TLS is reset. |
| ⚠️ | The domain looks like a tracker/ad network per the heuristic. Hint only: the decision is yours. |
| ⚠️ (system) | The app is a system app or shares its UID with system processes. Blocking it may affect more than it seems. |

The full legend is duplicated on the Settings tab inside the app.

## How it works

```
vpn/       VpnService: packet reads from tun, user-space forwarding,
           RST injection, DNS sinkhole, domain rules
parser/    hand-rolled IPv4 / TCP / UDP parsing, SNI extraction from
           ClientHello, DNS answer parsing (no external libraries)
resolver/  UID → app (cache, shared-UID and system-UID handling)
ui/        three tabs: Connections, Apps, Settings (legend, about)
```

Connection aggregation key: `(app, domain-or-IP, port, protocol)`. When a domain becomes
known later, accumulated stats migrate from the IP key to the domain key, so CDN address
rotation does not produce duplicate rows.

## Privacy

- The VPN permission is used **only** for local traffic interception.
- Payloads are never logged or stored; only metadata is kept (addresses, ports, volumes,
  timestamps).
- All data stays on the device. The app itself has no servers, no telemetry and no
  outbound calls of its own.
- The code is open (GPLv3): every claim above can be verified by reading the repository.

> The app routes all device traffic through itself. Build only from this repository's
> sources.

## Limitations & known issues

- **IPv6** is not parsed or displayed.
- **Private DNS (DoT/DoH)**: with a private DNS enabled, other apps' DNS queries are
  encrypted — per-app DNS is not visible (rows show system UIDs 1051/0), the sinkhole
  cannot act; domain rules work via SNI only.
- **QUIC (UDP:443)**: SNI is encrypted — such connections show a raw IP without a domain.
- **STARTTLS (XMPP 5222, SMTP 587, etc.)**: TLS starts mid-stream, SNI is not extracted —
  raw IP without a domain.
- A domain may stay unknown if the DNS answer and ClientHello did not pass through the
  tunnel (app resolver cache).
- The connection list lives in memory and resets on service restart (rules and known
  domains do not).
- The tracker heuristic is not an AdGuard/pi-hole-grade blocklist: misses and false
  positives are possible. ⚠️ is a hint, not a verdict.
- Blocking system apps or shared-UID apps (e.g. UID 1000) may affect adjacent system
  functions — the app warns, but the decision remains the user's.

## Requirements & build

- Android 10 (API 29)+: below that, per-app attribution is unavailable.
- Android Studio Koala or newer; Gradle sync; run on a physical device (VPN behaves
  unstably on emulators).
- On first start the system shows a VPN connection dialog — this is the standard
  interception mechanism; no traffic leaves the device.

```bash
git clone https://github.com/ramatafu/TrafficMonitor
# open in Android Studio, Run
```

## Project status & contributions

The project is in **maintenance mode**: brought to a finished state and feature-frozen.
Pull requests with fixes and compatibility restoration for new Android versions are
welcome; new features are not accepted. Future ideas are recorded in issues with the
`idea` label and are not planned for implementation.

## Credits

- Idea, design, testing: **ram**
- Code generation: **Claude Code (Anthropic)**
- Consultation: **DeepSeek**
- Architecture & security review: **Qwen (Alibaba)**

## License

GNU General Public License v3.0 (GPLv3). See [LICENSE](LICENSE).

<img width="1536" height="1024" alt="изображение" src="https://github.com/user-attachments/assets/8f0be310-6c1f-45ee-894c-4eeb7abfa4fd" />
# Traffic Monitor

> Локальный монитор сетевого трафика и файрвол для Android.
> Всё происходит на устройстве: без серверов, без аналитики, без передачи данных
> третьим лицам.

**Статус: maintenance mode.** Новые фичи не добавляются; поддерживается совместимость
с новыми версиями Android и исправление регрессий. Текущий релиз: `v0.1.0`.
Лицензия: GPLv3.

**Языки:** [English](README.md) · Русский (этот файл). В самом приложении интерфейс
по умолчанию английский; русский включается тумблером в настройках.

## Что это

Приложение поднимает локальный VPN (`VpnService`), перехватывает IPv4-трафик устройства
и показывает в реальном времени: **какое приложение → куда стучится → сколько байт**.
Трафик форвардится дальше (user-space NAT), поэтому интернет при активном мониторинге
работает. Помимо наблюдения, приложение умеет блокировать: всё приложение целиком или
отдельные домены (например, телеметрию), не трогая основную функцию.

Root не нужен. Внешних библиотек для разбора пакетов нет — заголовки IPv4/TCP/UDP
парсятся вручную.

## Возможности

- **Живой список соединений**: приложение → домен/IP:порт, протокол, байты ↑/↓,
  число сессий, время последней активности.
- **Определение владельца пакета** по UID (`ConnectivityManager.getConnectionOwnerUid`,
  API 29+).
- **Домены вместо IP**: из DNS-ответов и TLS SNI; кэш соответствий IP↔домен; строки
  переагрегируются, когда домен становится известен позже.
- **Блокировка приложения** (тумблер): трафик не пересылается, живые сессии
  принудительно разрываются (TCP RST обратно в tun, сброс UDP-маппингов); счётчик
  «попыток» показывает, что приложение продолжает стучаться в стену.
- **Блокировка домена** (тап по строке, правило действует для всех приложений):
  DNS-запросы получают NXDOMAIN (локальный sinkhole), TLS-соединения обрываются по SNI.
- **Подсказки о трекерах**: эвристический список (включая OEM-телеметрию прошивок)
  плюс токены в именах (`analytics`, `telemetry`, …). Значок ⚠️ — только подсказка,
  ничего не блокируется автоматически.
- **Защита от выстрела в ногу**: системные приложения и приложения с общим UID
  помечаются ⚠️, перед блокировкой запрашивается подтверждение.
- **Персистентность**: правила блокировки и известные домены хранятся в Room
  и переживают перезапуск сервиса.
- **Локализация**: интерфейс по умолчанию английский; русский включается тумблером
  в настройках.

## Значки в списке соединений

| Значок | Значение |
|---|---|
| 🚫 | Приложение заблокировано целиком (вкладка «Приложения»). Трафик не пересылается; счётчик «попыток» растёт. |
| ⛔ | Домен заблокирован для всех приложений (тап по строке — включить/выключить). DNS → NXDOMAIN, TLS обрывается. |
| ⚠️ | Домен похож на трекер/рекламную сеть по эвристике. Только подсказка: решение за вами. |
| ⚠️ (системное) | Приложение системное или делит UID с системными процессами. Блокировка может задеть больше, чем кажется. |

Полная легенда продублирована на вкладке настроек внутри приложения.

## Как это устроено

```
vpn/       VpnService: чтение пакетов из tun, user-space форвардинг,
           инъекции RST, DNS-sinkhole, доменные правила
parser/    ручной разбор IPv4 / TCP / UDP, извлечение SNI из ClientHello,
           разбор DNS-ответов (без внешних библиотек)
resolver/  UID → приложение (кэш, обработка shared UID и системных UID)
ui/        три вкладки: Соединения, Приложения, Настройки (легенда, о приложении)
```

Агрегация соединений: ключ `(приложение, домен-или-IP, порт, протокол)`; при появлении
домена накопленная статистика переносится с IP-ключа на доменный, чтобы CDN с ротацией
адресов не плодили дубли.

## Приватность

- Разрешение VPN используется **только** для локального перехвата трафика.
- Полезная нагрузка не логируется и не хранится; ведутся только метаданные
  (адреса, порты, объёмы, время).
- Все данные живут на устройстве. Никаких серверов, телеметрии и внешних запросов
  у самого приложения нет.
- Код открыт (GPLv3): любое утверждение выше можно проверить чтением репозитория.

> Приложение маршрутизирует через себя весь трафик устройства. Собирайте сборки
> только из исходников этого репозитория.

## Ограничения и известные проблемы

- **IPv6** не парсируется и не отображается.
- **Private DNS (DoT/DoH)**: если в системе включён частный DNS, чужие DNS-запросы
  зашифрованы — per-app DNS не виден (строки будут от системных UID 1051/0),
  sinkhole бессилен; доменные правила работают только по SNI.
- **QUIC (UDP:443)**: SNI зашифрован — такие соединения отображаются сырым IP
  без домена.
- **STARTTLS (XMPP 5222, SMTP 587 и т.п.)**: TLS начинается посреди потока, SNI
  не извлекается — сырой IP без домена.
- Домен может остаться неизвестным, если DNS-ответ и ClientHello не прошли через
  туннель (кэш резолва приложения).
- Список соединений хранится в памяти и обнуляется при перезапуске сервиса
  (правила и домены — нет).
- Эвристика трекеров — не блокировочный список уровня AdGuard/pi-hole: возможны
  пропуски и ложные срабатывания. ⚠️ — подсказка, а не вердикт.
- Блокировка системных приложений и приложений с общим UID (например, UID 1000)
  может повлиять на смежные системные функции — приложение предупреждает, но
  ответственность за решение остаётся за пользователем.

## Требования и сборка

- Android 10 (API 29) и выше: ниже атрибуция пакетов по приложениям недоступна.
- Android Studio Koala или новее; Gradle-синхронизация; запуск на физическом
  устройстве (на эмуляторах VPN ведёт себя нестабильно).
- При первом запуске система покажет диалог создания VPN-соединения — это штатный
  механизм перехвата, трафик никуда не отправляется.

```bash
git clone https://github.com/ramatafu/TrafficMonitor
# открыть в Android Studio, Run
```

## Статус проекта и вклад

Проект в **maintenance mode**: доведён до законченного вида и заморожен по фичам.
Pull request с исправлениями и восстановлением совместимости с новыми версиями Android
приветствуются; новые фичи не принимаются. Идеи на будущее фиксируются в issues
с меткой `idea` и реализовываться не планируются.

## Титры

- Идея, дизайн, тестирование: **ram**
- Генерация кода: **Claude Code (Anthropic)**
- Консультация: **DeepSeek**
- Архитектурное ревью и ревью безопасности: **Qwen (Alibaba)**

## Лицензия

GNU General Public License v3.0 (GPLv3). См. [LICENSE](LICENSE).
