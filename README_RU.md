# GOD OF TRADING — Android app

## Подготовка обновления 0.6.4
Ветка: `feature/binarium-data-engine`. Ветка `main` не изменяется.

### Реализуемые возможности
- Supabase Auth: регистрация и вход по Email/паролю.
- Google OAuth через системный браузер и PKCE callback в приложение.
- Восстановление пароля по Email.
- Хранение access/refresh токенов в EncryptedSharedPreferences + Android Keystore.
- Индивидуальные настройки аккаунта: выбор 1–10 валютных пар, экспирация 3/5 минут, согласие на обмен сигналами.
- Многоинструментальный ручной анализ: EUR/USD, GBP/USD, USD/JPY, USD/CHF, USD/CAD, AUD/USD, NZD/USD, EUR/JPY, GBP/JPY, EUR/GBP.
- Двусторонние приглашения и передача направленных сигналов между принявшими приглашение аккаунтами.
- Только русские сигналы в интерфейсе: ВВЕРХ, ВНИЗ, ОЖИДАНИЕ.
- Никаких автоматических сделок.

### Настройка Supabase
1. В GitHub Actions Secrets должны быть `SUPABASE_URL` и `SUPABASE_ANON_KEY`. Используется только публичный anon/publishable key; `service_role` в Android не помещать.
2. В Supabase SQL Editor выполнить миграции по порядку:
   - `supabase/migrations/202611100001_shared_signals.sql`
   - `supabase/migrations/202611100002_trading_settings.sql`
3. В Supabase Authentication → URL Configuration → Redirect URLs добавить:
   `com.potraderbot://auth/callback`
   и разрешить тот же callback с query-параметром для восстановления пароля.
4. В Authentication → Providers включить Email и Google. Для Google в Google Cloud OAuth client добавить Supabase callback URL вида `https://<project-ref>.supabase.co/auth/v1/callback` в Authorized redirect URIs.
5. Включить Email confirmation по желанию. Если включено, пользователь подтверждает адрес из письма до первого входа.

### Обмен сигналами
- Владелец аккаунта отправляет другу свой UUID из раздела «ОБМЕН СИГНАЛАМИ».
- Друг вводит UUID, получает приглашение и должен принять его.
- Сигналы автоматически передаются только при включённом пользователем разрешении на обмен и существующем принятом подключении.
- Передаются только направленные сигналы ВВЕРХ/ВНИЗ; ОЖИДАНИЕ не рассылается.
- Можно отключить обмен в индивидуальных настройках. Не передаются пароли или брокерские учётные данные.

## Аналитический движок
- Источник минутных OHLC-свечей: Twelve Data.
- Экономический календарь: API.SH (посредник, не официальный API Investing.com).
- Индикаторы и фильтры: EMA, RSI, MACD, ATR, ADX proxy, Bollinger, структура, свечные модели и сценарии.
- Проверка свежести/качества свечей и новостного риска.
- Сценарные оценки — эвристические баллы, не откалиброванные вероятности и не гарантия результата.
- Котировки Twelve Data могут отличаться от Binarium/Pocket Option. Сверяй котировку на платформе.
- Анализ выполняется только вручную по нажатию кнопки.

## Защищённые ключи
- Twelve Data и API.SH вводятся в приложении и хранятся локально зашифрованно.
- Supabase URL и публичный anon key передаются в сборку через GitHub Actions Secrets.
- Никогда не использовать `service_role` в клиентском APK и не отправлять секреты в чат.

## Сборка и выпуск
- Package ID: `com.potraderbot`.
- Подпись проверяется на совместимость с установленной v0.5.9.
- Для v0.6.4 задан `versionCode=16`, `versionName=0.6.4`.
- Workflow пока собирает и проверяет APK как validation artifact. Публикация Release намеренно отключена до прохождения полного набора проверок.
