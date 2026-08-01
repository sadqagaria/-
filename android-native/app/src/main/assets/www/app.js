'use strict';

const CAIRO = { lat: 30.0444, lon: 31.2357, timezone: 'Africa/Cairo' };
const API = {
  weather: `https://api.open-meteo.com/v1/forecast?latitude=${CAIRO.lat}&longitude=${CAIRO.lon}&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,rain,weather_code,cloud_cover,pressure_msl,wind_speed_10m,wind_direction_10m,wind_gusts_10m&hourly=temperature_2m,precipitation_probability,precipitation,weather_code,wind_speed_10m,wind_gusts_10m&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,wind_speed_10m_max,wind_gusts_10m_max,sunrise,sunset,uv_index_max&timezone=Africa%2FCairo&forecast_days=7`,
  prayer: 'https://api.aladhan.com/v1/timingsByCity?city=Cairo&country=Egypt&method=5',
  gold: 'https://api.gold-api.com/price/XAU',
  fxBase: 'https://api.frankfurter.dev/v2/rate'
};

const CURRENCIES = {
  EGP: 'الجنيه المصري', USD: 'الدولار الأمريكي', EUR: 'اليورو', GBP: 'الجنيه الإسترليني',
  SAR: 'الريال السعودي', AED: 'الدرهم الإماراتي', KWD: 'الدينار الكويتي', QAR: 'الريال القطري'
};
const CURRENCIES_EN = {
  EGP: 'Egyptian Pound', USD: 'US Dollar', EUR: 'Euro', GBP: 'British Pound',
  SAR: 'Saudi Riyal', AED: 'UAE Dirham', KWD: 'Kuwaiti Dinar', QAR: 'Qatari Riyal'
};
const FX_CODES = Object.keys(CURRENCIES).filter(code => code !== 'EGP');
const PRAYERS = [
  ['Fajr', 'الفجر'], ['Sunrise', 'الشروق'], ['Dhuhr', 'الظهر'],
  ['Asr', 'العصر'], ['Maghrib', 'المغرب'], ['Isha', 'العشاء']
];
const SALAH_KEYS = PRAYERS.filter(([key]) => key !== 'Sunrise');
const PRAYER_EN = { Fajr:'Fajr', Sunrise:'Sunrise', Dhuhr:'Dhuhr', Asr:'Asr', Maghrib:'Maghrib', Isha:'Isha' };
const CACHE_KEYS = {
  weather: 'cairoPulse.weather.v1', fx: 'cairoPulse.fx.v1', gold: 'cairoPulse.gold.v1', prayer: 'cairoPulse.prayer.v1'
};
const SETTINGS_KEY = 'cairoPulse.settings.v2';
const TIMER_KEY = 'cairoPulse.timer.v2';
const STOPWATCH_KEY = 'cairoPulse.stopwatch.v2';
const CALC_KEY = 'cairoPulse.calc.v2';
const SPEED_HISTORY_KEY = 'cairoPulse.speedHistory.v2';
const ADHAN_DB = 'cairoPulse.audio.v1';

const state = {
  weather: null,
  fx: { rates: { EGP: 1 }, date: null, source: null },
  gold: null,
  prayer: null,
  settings: {
    theme: 'auto', accent: 'cyan', language: 'ar', showSeconds: true, hour12: false, clockStyle: 'classic',
    clockMode: 'analog', analogStyle: 'classic', analogNumerals: 'western', digitalStyle: 'segment', digitalDigits: 'western',
    weatherInterval: 15, moneyInterval: 30, prayerAlert: false, prayerAlertMinutes: 10,
    adhanPlayback: true, prayerOffsets: { Fajr: 0, Dhuhr: 0, Asr: 0, Maghrib: 0, Isha: 0 },
    backgroundUpdates: true, goldPremium: 0, stepSensitivity: 'medium', stepGoal: 6000
  },
  intervals: {},
  installPrompt: null,
  prayerAlerted: new Set(),
  timer: { running: false, endAt: 0, remainingMs: 300000, raf: 0 },
  stopwatch: { running: false, startedAt: 0, startedEpoch: 0, elapsedMs: 0, raf: 0, laps: [] },
  pedometer: { active: false, native: false, steps: 0, date: '', gravity: 9.81, above: false, lastStep: 0, listener: null },
  calc: { expression: '', history: [] },
  speedHistory: [],
  native: { available: false, statusTimer: 0, adhanObjectUrl: null },
  ui: { minuteKey: '', rainSignature: '', lastWidgetSync: '' },
  toastTimer: null
};

const $ = (id) => document.getElementById(id);
const currentLang = () => state.settings.language === 'en' ? 'en' : 'ar';
const tr = (ar, en) => currentLang() === 'en' ? en : ar;
const appLocale = () => currentLang() === 'en' ? 'en-GB' : 'ar-EG';
const currencyName = code => currentLang() === 'en' ? (CURRENCIES_EN[code] || code) : (CURRENCIES[code] || code);
const prayerName = key => currentLang() === 'en' ? (PRAYER_EN[key] || key) : (PRAYERS.find(p => p[0] === key)?.[1] || key);
function applyLanguage(refreshDynamic = true) {
  const lang = currentLang();
  document.documentElement.lang = lang;
  document.documentElement.dir = lang === 'ar' ? 'rtl' : 'ltr';
  document.title = lang === 'ar' ? 'نبض القاهرة | Cairo Pulse' : 'Cairo Pulse | نبض القاهرة';
  document.querySelectorAll('[data-ar][data-en]').forEach(el => {
    const value = el.dataset[lang];
    if (typeof value === 'string') el.textContent = value;
  });
  document.querySelectorAll('[data-title-ar][data-title-en]').forEach(el => {
    const value = lang === 'ar' ? el.dataset.titleAr : el.dataset.titleEn;
    if (value) { el.title = value; el.setAttribute('aria-label', value); }
  });
  const languageBtn = $('languageBtn');
  if (languageBtn) { languageBtn.textContent = lang === 'ar' ? 'EN' : 'ع'; languageBtn.title = lang === 'ar' ? 'English' : 'العربية'; }
  if ($('languageSelect')) $('languageSelect').value = lang;
  const brandMark = document.querySelector('.brand-mark'); if (brandMark) brandMark.textContent = lang === 'ar' ? 'ق' : 'C';
  const settingsLink = document.querySelector('a[href="#settings"].icon-link');
  if (settingsLink) { const label = tr('الإعدادات','Settings'); settingsLink.title = label; settingsLink.setAttribute('aria-label', label); }
  if (refreshDynamic) {
    updateClock(); updateConnectionStatus(); populateCurrencySelects(); convertCurrency();
    if (state.weather) renderWeather(state.weather, false);
    if (state.fx?.rates?.USD) renderFX(state.fx, false);
    if (state.gold) calculateGold(false);
    if (state.prayer) renderPrayer(state.prayer, false);
    updateDynamicScene(); updatePrayerTimeline(); syncHomeSummary();
    renderSteps(); renderStopwatch(); renderSpeedHistory(); refreshNativeStatus();
  }
}
const DYNAMIC_EN = {
  'تحديث الطقس…':'Updating weather…','تحديث العملات…':'Updating currencies…','تحديث الذهب…':'Updating gold…','تحديث مواقيت الصلاة…':'Updating prayer times…',
  'متصل':'Online','دون إنترنت':'Offline','غير متاح':'Unavailable','غير مدعوم':'Not supported','محفوظ':'Cached','مباشر':'Live','اليوم':'Today',
  'تم تحديث الطقس':'Weather updated','تعذر التحديث؛ تم عرض آخر طقس محفوظ':'Update failed; showing cached weather','تعذر تحميل بيانات الطقس':'Unable to load weather data','تعذر الاتصال بمصدر الطقس':'Unable to reach weather source',
  'تم تحديث أسعار العملات':'Currency rates updated','تم عرض آخر أسعار عملات محفوظة':'Showing cached currency rates','تعذر تحميل أسعار العملات':'Unable to load currency rates',
  'تم تحديث سعر الذهب':'Gold price updated','تم عرض آخر سعر ذهب محفوظ':'Showing cached gold price','تعذر تحميل سعر الذهب':'Unable to load gold price',
  'تم تحديث مواقيت الصلاة':'Prayer times updated','تم عرض آخر مواقيت محفوظة':'Showing cached prayer times','تعذر تحميل مواقيت الصلاة':'Unable to load prayer times',
  'يعمل…':'Running…','ضعيف':'Poor','متوسط':'Average','جيد':'Good','ممتاز':'Excellent','تقدير الجهاز':'Device estimate','تعذر الاختبار':'Test failed',
  'انتهى المؤقت':'Timer finished','حدد مدة للمؤقت أولًا':'Set a timer duration first','إيقاف':'Stop','استكمال':'Resume','بدء':'Start','تشغيل العداد':'Start counter','إيقاف العداد':'Stop counter','متوقف':'Stopped',
  'Android أصلي':'Native Android','يعمل بالخلفية':'Running in background','لا يوجد حساس متاح':'No sensor available','الحساس غير متاح':'Sensor unavailable','مسموح':'Allowed','يحتاج موافقة':'Permission required','مفعّل دوريًا':'Enabled periodically','غير مفعّل':'Disabled','لم يحدث بعد':'Not updated yet','غير مجدولة':'Not scheduled',
  'الصوت الحالي: نغمة التطبيق الافتراضية':'Current sound: default app chime','تم تصفير خطوات اليوم':'Today’s steps reset','تم تشغيل عداد الخطوات':'Step counter started','يعمل أثناء فتح التطبيق':'Runs while app is open',
  'خطأ':'Error','عملية غير صالحة':'Invalid operation','لا يمكن القسمة على صفر':'Cannot divide by zero','نتيجة غير صالحة':'Invalid result','لا يوجد جذر حقيقي لعدد سالب':'No real square root for a negative number'
};
function localMessage(value) { return currentLang() === 'en' ? (DYNAMIC_EN[value] || value) : value; }
const clamp = (n, min, max) => Math.min(max, Math.max(min, n));
const round = (n, digits = 0) => Number.isFinite(Number(n)) ? Number(n).toFixed(digits) : '—';
const nowStamp = () => new Date().toISOString();

function hasNativeBridge() {
  return Boolean(window.CairoNative && typeof window.CairoNative.getStatus === 'function');
}
function nativeCall(method, ...args) {
  if (!hasNativeBridge() || typeof window.CairoNative[method] !== 'function') return null;
  try { return window.CairoNative[method](...args); }
  catch (error) { console.warn(`Native call failed: ${method}`, error); return null; }
}
function parseNativeJson(value, fallback = {}) {
  try { return typeof value === 'string' ? JSON.parse(value) : (value || fallback); }
  catch (_) { return fallback; }
}
function openAudioDb() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(ADHAN_DB, 1);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains('audio')) db.createObjectStore('audio');
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}
async function saveWebAdhanFile(file) {
  const db = await openAudioDb();
  await new Promise((resolve, reject) => {
    const tx = db.transaction('audio', 'readwrite');
    tx.objectStore('audio').put({ blob: file, name: file.name, type: file.type, savedAt: nowStamp() }, 'customAdhan');
    tx.oncomplete = resolve;
    tx.onerror = () => reject(tx.error);
  });
  db.close();
}
async function getWebAdhanFile() {
  const db = await openAudioDb();
  const value = await new Promise((resolve, reject) => {
    const tx = db.transaction('audio', 'readonly');
    const request = tx.objectStore('audio').get('customAdhan');
    request.onsuccess = () => resolve(request.result || null);
    request.onerror = () => reject(request.error);
  });
  db.close();
  return value;
}
async function clearWebAdhanFile() {
  const db = await openAudioDb();
  await new Promise((resolve, reject) => {
    const tx = db.transaction('audio', 'readwrite');
    tx.objectStore('audio').delete('customAdhan');
    tx.oncomplete = resolve;
    tx.onerror = () => reject(tx.error);
  });
  db.close();
  stopWebAdhan();
}
async function playWebAdhan() {
  const saved = await getWebAdhanFile().catch(() => null);
  if (!saved?.blob) {
    $('alertAudio').play().catch(() => {});
    return false;
  }
  if (state.native.adhanObjectUrl) URL.revokeObjectURL(state.native.adhanObjectUrl);
  state.native.adhanObjectUrl = URL.createObjectURL(saved.blob);
  const audio = $('alertAudio');
  audio.src = state.native.adhanObjectUrl;
  await audio.play();
  return true;
}
function stopWebAdhan() {
  const audio = $('alertAudio');
  audio.pause();
  audio.currentTime = 0;
}
function getCairoOffsetMinutes(date = new Date()) {
  const part = new Intl.DateTimeFormat('en-US', {
    timeZone: CAIRO.timezone, timeZoneName: 'longOffset'
  }).formatToParts(date).find(x => x.type === 'timeZoneName')?.value || 'GMT+02:00';
  const match = part.match(/GMT([+-])(\d{2}):(\d{2})/);
  if (!match) return 120;
  const minutes = Number(match[2]) * 60 + Number(match[3]);
  return match[1] === '-' ? -minutes : minutes;
}
function cairoDateParts(date = new Date()) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: CAIRO.timezone, year: 'numeric', month: '2-digit', day: '2-digit'
  }).formatToParts(date);
  const value = type => Number(parts.find(p => p.type === type)?.value);
  return { year: value('year'), month: value('month'), day: value('day') };
}
function cairoDateAtTime(time, addDay = 0) {
  const [hour, minute] = cleanPrayerTime(time).split(':').map(Number);
  const parts = cairoDateParts();
  let guess = new Date(Date.UTC(parts.year, parts.month - 1, parts.day + addDay, hour, minute, 0));
  const offset = getCairoOffsetMinutes(guess);
  return new Date(guess.getTime() - offset * 60000);
}

const cairoDateKey = (date = new Date()) => new Intl.DateTimeFormat('en-CA', {
  timeZone: CAIRO.timezone, year: 'numeric', month: '2-digit', day: '2-digit'
}).format(date);

function loadSettings() {
  try {
    const saved = JSON.parse(localStorage.getItem(SETTINGS_KEY) || '{}');
    state.settings = {
      ...state.settings,
      ...saved,
      prayerOffsets: { ...state.settings.prayerOffsets, ...(saved.prayerOffsets || {}) }
    };
  } catch (_) { /* ignore corrupt settings */ }
}
function saveSettings() { localStorage.setItem(SETTINGS_KEY, JSON.stringify(state.settings)); }
function saveCache(key, data) { localStorage.setItem(key, JSON.stringify({ savedAt: nowStamp(), data })); }
function loadCache(key) {
  try { return JSON.parse(localStorage.getItem(key) || 'null'); } catch (_) { return null; }
}
function formatNumber(value, digits = 2) {
  if (!Number.isFinite(Number(value))) return '—';
  return new Intl.NumberFormat(appLocale(), { maximumFractionDigits: digits, minimumFractionDigits: digits }).format(Number(value));
}
function formatTime(date, seconds = true) {
  const digitLocale = state.settings.digitalDigits === 'eastern' ? 'ar-EG' : 'en-GB';
  return new Intl.DateTimeFormat(digitLocale, {
    timeZone: CAIRO.timezone, hour: '2-digit', minute: '2-digit', second: seconds ? '2-digit' : undefined,
    hour12: state.settings.hour12
  }).format(date);
}
function formatDateTime(dateLike) {
  const d = dateLike ? new Date(dateLike) : new Date();
  if (Number.isNaN(d.getTime())) return '—';
  return new Intl.DateTimeFormat(appLocale(), {
    timeZone: CAIRO.timezone, day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit'
  }).format(d);
}
function showToast(message, duration = 3200) {
  const el = $('toast');
  el.textContent = localMessage(message);
  el.classList.add('show');
  clearTimeout(state.toastTimer);
  state.toastTimer = setTimeout(() => el.classList.remove('show'), duration);
}
function setFooter(message) { $('footerStatus').textContent = localMessage(message); }

async function fetchJSON(url, options = {}, timeoutMs = 12000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, { ...options, signal: controller.signal, cache: 'no-store' });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return await response.json();
  } finally { clearTimeout(timer); }
}


function cairoClockParts(date = new Date()) {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: CAIRO.timezone, hour:'2-digit', minute:'2-digit', second:'2-digit', hour12:false
  }).formatToParts(date);
  const value = type => Number(parts.find(p => p.type === type)?.value || 0);
  return { hour:value('hour') % 24, minute:value('minute'), second:value('second') };
}
function timeStringToMinutes(value) {
  const match = String(value || '').match(/(?:T|^)(\d{1,2}):(\d{2})/);
  return match ? Number(match[1]) * 60 + Number(match[2]) : NaN;
}
function currentScenePhase() {
  const now = cairoClockParts();
  const currentMinutes = now.hour * 60 + now.minute;
  const sunrise = timeStringToMinutes(state.weather?.daily?.sunrise?.[0]) || timeStringToMinutes(state.prayer?.timings?.Sunrise) || 360;
  const sunset = timeStringToMinutes(state.weather?.daily?.sunset?.[0]) || timeStringToMinutes(state.prayer?.timings?.Maghrib) || 1080;
  if (currentMinutes >= sunrise - 65 && currentMinutes < sunrise + 45) return 'dawn';
  if (currentMinutes >= sunset - 50 && currentMinutes < sunset + 50) return 'sunset';
  if (currentMinutes >= sunrise + 45 && currentMinutes < sunset - 50) return 'day';
  return 'night';
}
function weatherSceneType() {
  const code = Number(state.weather?.current?.weather_code);
  if ([95,96,99].includes(code)) return 'storm';
  if ([51,53,55,61,63,65,80,81,82].includes(code) || Number(state.weather?.current?.precipitation || 0) > 0) return 'rain';
  if ([2,3,45,48].includes(code) || Number(state.weather?.current?.cloud_cover || 0) >= 65) return 'cloudy';
  return 'clear';
}
function renderRainEffects(enabled, strong = false) {
  const host = $('weatherEffects');
  if (!host) return;
  const signature = `${enabled}-${strong}`;
  if (state.ui.rainSignature === signature) return;
  state.ui.rainSignature = signature;
  host.innerHTML = '';
  if (!enabled) return;
  const count = strong ? 42 : 26;
  for (let i = 0; i < count; i++) {
    const drop = document.createElement('i');
    drop.className = 'rain-drop';
    drop.style.left = `${(i * 37.7) % 108 - 4}%`;
    drop.style.animationDuration = `${0.62 + (i % 7) * 0.09}s`;
    drop.style.animationDelay = `${-(i % 13) * 0.11}s`;
    drop.style.opacity = String(0.25 + (i % 5) * 0.09);
    host.appendChild(drop);
  }
}
function updateDynamicScene() {
  const phase = currentScenePhase();
  const weatherType = weatherSceneType();
  document.body.classList.remove('scene-dawn','scene-day','scene-sunset','scene-night','weather-clear','weather-cloudy','weather-rain','weather-storm');
  document.body.classList.add(`scene-${phase}`, `weather-${weatherType}`);
  renderRainEffects(weatherType === 'rain' || weatherType === 'storm', weatherType === 'storm');
  const phaseLabels = {
    dawn: tr('فترة الفجر والشروق','Dawn and sunrise'),
    day: tr('فترة النهار','Daytime'),
    sunset: tr('فترة الغروب','Sunset period'),
    night: tr('فترة الليل','Nighttime')
  };
  if ($('timelinePhaseLabel')) $('timelinePhaseLabel').textContent = phaseLabels[phase];
  const themeColor = phase === 'day' ? '#1766a0' : phase === 'dawn' ? '#9b5e48' : phase === 'sunset' ? '#7e3f4e' : '#061329';
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', themeColor);
}
function updatePrayerTimeline() {
  const host = $('timelinePrayerPoints');
  const marker = $('timelineNowMarker');
  if (!host || !marker || !state.prayer?.timings) return;
  const nowParts = cairoClockParts();
  const nowMinutes = nowParts.hour * 60 + nowParts.minute + nowParts.second / 60;
  const nowPercent = clamp(nowMinutes / 1440 * 100, 1.2, 98.8);
  marker.style.left = `${nowPercent}%`;
  if ($('timelineProgress')) $('timelineProgress').style.width = `${nowPercent}%`;
  if ($('timelineNowTime')) $('timelineNowTime').textContent = `${String(nowParts.hour).padStart(2,'0')}:${String(nowParts.minute).padStart(2,'0')}`;
  const sunriseMinutes = timeStringToMinutes(cleanPrayerTime(state.prayer.timings.Sunrise));
  const maghribMinutes = timeStringToMinutes(adjustedPrayerTime('Maghrib'));
  const isDay = Number.isFinite(sunriseMinutes) && Number.isFinite(maghribMinutes) && nowMinutes >= sunriseMinutes && nowMinutes < maghribMinutes;
  if ($('timelineNowIcon')) $('timelineNowIcon').textContent = isDay ? '☀️' : '🌙';
  const next = nextPrayerInfo();
  host.innerHTML = '';
  PRAYERS.forEach(([key], index) => {
    const shown = key === 'Sunrise' ? cleanPrayerTime(state.prayer.timings[key]) : adjustedPrayerTime(key);
    const minutes = timeStringToMinutes(shown);
    if (!Number.isFinite(minutes)) return;
    const point = document.createElement('div');
    point.className = `timeline-prayer-point ${index % 2 ? 'below' : 'above'} ${next?.key === key ? 'active' : ''}`;
    point.style.left = `${clamp(minutes / 1440 * 100, 1.2, 98.8)}%`;
    point.innerHTML = `<span>${prayerName(key)}</span><strong>${shown}</strong><i></i>`;
    host.appendChild(point);
  });
  const phase = currentScenePhase();
  const captions = {
    dawn: tr('أنت الآن في فترة الفجر والشروق','You are now in the dawn and sunrise period'),
    day: tr('أنت الآن في فترة النهار','You are now in the daytime period'),
    sunset: tr('أنت الآن في فترة الغروب','You are now in the sunset period'),
    night: tr('أنت الآن في فترة الليل','You are now in the nighttime period')
  };
  if ($('timelineCaption')) $('timelineCaption').textContent = captions[phase];
}

function weatherInfo(code, isDay = 1) {
  const ar = {
    0:[isDay?'☀️':'🌙','سماء صافية'],1:[isDay?'🌤️':'🌙','صحو غالبًا'],2:['⛅','غيوم متفرقة'],3:['☁️','غائم'],45:['🌫️','ضباب'],48:['🌫️','ضباب متجمد'],51:['🌦️','رذاذ خفيف'],53:['🌦️','رذاذ'],55:['🌧️','رذاذ كثيف'],61:['🌦️','مطر خفيف'],63:['🌧️','مطر'],65:['🌧️','مطر غزير'],71:['🌨️','ثلج خفيف'],73:['🌨️','ثلج'],75:['❄️','ثلج كثيف'],80:['🌦️','زخات خفيفة'],81:['🌧️','زخات مطر'],82:['⛈️','زخات قوية'],95:['⛈️','عاصفة رعدية'],96:['⛈️','عاصفة وبَرَد'],99:['⛈️','عاصفة قوية']
  };
  const en = {
    0:[isDay?'☀️':'🌙','Clear sky'],1:[isDay?'🌤️':'🌙','Mostly clear'],2:['⛅','Partly cloudy'],3:['☁️','Overcast'],45:['🌫️','Fog'],48:['🌫️','Rime fog'],51:['🌦️','Light drizzle'],53:['🌦️','Drizzle'],55:['🌧️','Heavy drizzle'],61:['🌦️','Light rain'],63:['🌧️','Rain'],65:['🌧️','Heavy rain'],71:['🌨️','Light snow'],73:['🌨️','Snow'],75:['❄️','Heavy snow'],80:['🌦️','Light showers'],81:['🌧️','Rain showers'],82:['⛈️','Heavy showers'],95:['⛈️','Thunderstorm'],96:['⛈️','Thunderstorm with hail'],99:['⛈️','Severe thunderstorm']
  };
  return (currentLang()==='en'?en:ar)[code] || ['🌡️',tr('حالة جوية','Weather condition')];
}
function windDirection(deg) {
  const dirs = currentLang()==='en' ? ['N','NE','E','SE','S','SW','W','NW'] : ['شمال','شمال شرقي','شرق','جنوب شرقي','جنوب','جنوب غربي','غرب','شمال غربي'];
  return dirs[Math.round((Number(deg) || 0) / 45) % 8];
}

async function refreshWeather(silent = false) {
  const btn = $('weatherRefreshBtn');
  btn?.classList.add('loading');
  setFooter('تحديث الطقس…');
  try {
    const data = await fetchJSON(API.weather);
    if (!data.current || !data.daily) throw new Error('بيانات الطقس غير مكتملة');
    state.weather = data;
    saveCache(CACHE_KEYS.weather, data);
    renderWeather(data, false);
    if (!silent) showToast('تم تحديث الطقس');
  } catch (error) {
    const cached = loadCache(CACHE_KEYS.weather);
    if (cached?.data) {
      state.weather = cached.data;
      renderWeather(cached.data, true, cached.savedAt);
      if (!silent) showToast('تعذر التحديث؛ تم عرض آخر طقس محفوظ');
    } else {
      $('weatherDesc').textContent = tr('تعذر تحميل بيانات الطقس','Unable to load weather data');
      $('weatherLiveBadge').textContent = tr('غير متاح','Unavailable');
      if (!silent) showToast('تعذر الاتصال بمصدر الطقس');
    }
  } finally {
    btn?.classList.remove('loading');
    setFooter(navigator.onLine ? 'متصل' : 'دون إنترنت');
  }
}

function renderWeather(data, cached = false, cachedAt = null) {
  const c = data.current || {};
  const [icon, desc] = weatherInfo(Number(c.weather_code), Number(c.is_day ?? 1));
  $('weatherIcon').textContent = icon;
  $('currentTemp').textContent = round(c.temperature_2m, 0);
  $('weatherDesc').textContent = desc;
  $('feelsLike').textContent = `${round(c.apparent_temperature, 0)}°`;
  $('humidity').textContent = `${round(c.relative_humidity_2m, 0)}%`;
  $('windSpeed').textContent = `${round(c.wind_speed_10m, 0)} ${tr('كم/س','km/h')}`;
  $('windGust').textContent = `${round(c.wind_gusts_10m, 0)} ${tr('كم/س','km/h')}`;
  $('rainNow').textContent = `${round(c.precipitation ?? c.rain, 1)} ${tr('مم','mm')}`;
  $('pressureNow').textContent = `${round(c.pressure_msl, 0)} hPa`;
  $('windDirectionNow').textContent = windDirection(c.wind_direction_10m);
  $('cloudCoverNow').textContent = `${round(c.cloud_cover, 0)}%`;
  $('weatherLiveBadge').textContent = cached ? tr('محفوظ','Cached') : tr('مباشر','Live');
  $('weatherUpdated').textContent = `${tr('آخر تحديث','Last update')}: ${cached ? formatDateTime(cachedAt) : formatDateTime(c.time || new Date())} · ${tr('الرياح','Wind')} ${windDirection(c.wind_direction_10m)}`;
  updateHomeTodayWeather(data.daily);
  renderDailyForecast(data.daily);
  renderHourlyForecast(data.hourly);
  renderWeatherAlert(data.daily);
  updateDynamicScene();
  syncHomeSummary();
}

function updateHomeTodayWeather(daily) {
  if (!daily?.time?.length) return;
  const [icon, desc] = weatherInfo(Number(daily.weather_code?.[0]), 1);
  if ($('homeTodayWeatherIcon')) $('homeTodayWeatherIcon').textContent = icon;
  if ($('homeTodayWeatherDesc')) $('homeTodayWeatherDesc').textContent = desc;
  if ($('homeTodayMax')) $('homeTodayMax').textContent = `${round(daily.temperature_2m_max?.[0], 0)}°`;
  if ($('homeTodayMin')) $('homeTodayMin').textContent = `${round(daily.temperature_2m_min?.[0], 0)}°`;
  if ($('homeTodayRain')) $('homeTodayRain').textContent = `${round(daily.precipitation_probability_max?.[0], 0)}%`;
}

function renderDailyForecast(daily) {
  const host = $('dailyForecast');
  host.innerHTML = '';
  if (!daily?.time?.length) return;
  daily.time.forEach((dateStr, i) => {
    const d = new Date(`${dateStr}T12:00:00`);
    const day = new Intl.DateTimeFormat(appLocale(), { weekday: 'short', timeZone: CAIRO.timezone }).format(d);
    const date = new Intl.DateTimeFormat(appLocale(), { day: 'numeric', month: 'short', timeZone: CAIRO.timezone }).format(d);
    const [icon] = weatherInfo(Number(daily.weather_code?.[i]));
    const el = document.createElement('article');
    el.className = `forecast-day ${i === 0 ? 'active' : ''}`;
    el.innerHTML = `
      <h3>${i === 0 ? tr('اليوم','Today') : day}</h3><small>${date}</small>
      <div class="weather-emoji">${icon}</div>
      <div class="temps"><span>${round(daily.temperature_2m_max?.[i], 0)}°</span><span>${round(daily.temperature_2m_min?.[i], 0)}°</span></div>
      <div class="forecast-meta"><span>${tr('احتمال المطر','Rain chance')}: ${round(daily.precipitation_probability_max?.[i], 0)}%</span><span>${tr('الكمية','Amount')}: ${round(daily.precipitation_sum?.[i], 1)} ${tr('مم','mm')}</span><span>${tr('رياح','Wind')}: ${round(daily.wind_speed_10m_max?.[i], 0)} ${tr('كم/س','km/h')}</span><span>${tr('هبّات','Gusts')}: ${round(daily.wind_gusts_10m_max?.[i], 0)} ${tr('كم/س','km/h')}</span><span>UV: ${round(daily.uv_index_max?.[i], 1)}</span><span>${tr('شروق','Sunrise')} ${String(daily.sunrise?.[i] || '').slice(11,16)} · ${tr('غروب','Sunset')} ${String(daily.sunset?.[i] || '').slice(11,16)}</span></div>`;
    host.appendChild(el);
  });
}

function renderHourlyForecast(hourly) {
  const host = $('hourlyForecast');
  host.innerHTML = '';
  if (!hourly?.time?.length) return;
  const current = Date.now();
  let start = hourly.time.findIndex(t => new Date(t).getTime() >= current - 30 * 60000);
  if (start < 0) start = 0;
  for (let i = start; i < Math.min(start + 12, hourly.time.length); i++) {
    const [icon] = weatherInfo(Number(hourly.weather_code?.[i]));
    const time = new Intl.DateTimeFormat(appLocale(), { hour: '2-digit', minute: '2-digit', hour12: state.settings.hour12 }).format(new Date(hourly.time[i]));
    const el = document.createElement('div');
    el.className = 'hour-card';
    el.innerHTML = `<h3>${time}</h3><div class="weather-emoji">${icon}</div><strong>${round(hourly.temperature_2m?.[i], 0)}°</strong><div class="forecast-meta"><span>${tr('مطر','Rain')} ${round(hourly.precipitation_probability?.[i], 0)}%</span><span>${tr('رياح','Wind')} ${round(hourly.wind_speed_10m?.[i], 0)}</span><span>${tr('هبّات','Gusts')} ${round(hourly.wind_gusts_10m?.[i], 0)}</span></div>`;
    host.appendChild(el);
  }
}
function renderWeatherAlert(daily) {
  const alert = $('weatherAlert');
  const rain = Math.max(...(daily?.precipitation_probability_max || [0]).map(Number));
  const gust = Math.max(...(daily?.wind_gusts_10m_max || [0]).map(Number));
  const notes = [];
  if (rain >= 50) notes.push(currentLang()==='en' ? `Rain chance may reach ${round(rain, 0)}% this week` : `احتمال أمطار يصل إلى ${round(rain, 0)}% خلال الأسبوع`);
  if (gust >= 45) notes.push(currentLang()==='en' ? `Wind gusts may reach ${round(gust, 0)} km/h` : `هبّات رياح قد تصل إلى ${round(gust, 0)} كم/س`);
  if (notes.length) { alert.textContent = `${tr('تنبيه','Alert')}: ${notes.join(currentLang()==='en' ? ', ' : '، ')}`; alert.classList.remove('hidden'); }
  else alert.classList.add('hidden');
}

async function fetchFxPair(code) {
  const urls = [
    `${API.fxBase}/${code}/EGP?providers=CBE`,
    `${API.fxBase}/${code}/EGP`
  ];
  let lastError;
  for (const url of urls) {
    try {
      const data = await fetchJSON(url, {}, 10000);
      const row = Array.isArray(data) ? data[0] : data;
      const rate = Number(row?.rate ?? row?.rates?.EGP);
      if (!Number.isFinite(rate) || rate <= 0) throw new Error('سعر غير صالح');
      return { code, rate, date: row?.date || data?.date || null, source: url.includes('providers=CBE') ? 'CBE' : 'Blended' };
    } catch (e) { lastError = e; }
  }
  throw lastError || new Error('تعذر جلب السعر');
}

async function refreshFX(silent = false) {
  $('moneyRefreshBtn')?.classList.add('loading');
  setFooter('تحديث العملات…');
  try {
    const results = await Promise.allSettled(FX_CODES.map(fetchFxPair));
    const rates = { EGP: 1 };
    let date = null;
    const sources = new Set();
    results.forEach(result => {
      if (result.status === 'fulfilled') {
        rates[result.value.code] = result.value.rate;
        date = result.value.date || date;
        sources.add(result.value.source);
      }
    });
    if (!rates.USD) throw new Error('تعذر جلب الدولار');
    state.fx = { rates, date, source: sources.has('CBE') ? 'CBE' : 'Blended' };
    saveCache(CACHE_KEYS.fx, state.fx);
    renderFX(state.fx, false);
    calculateGold();
    if (!silent) showToast('تم تحديث أسعار العملات');
  } catch (error) {
    const cached = loadCache(CACHE_KEYS.fx);
    if (cached?.data?.rates?.USD) {
      state.fx = cached.data;
      renderFX(state.fx, true, cached.savedAt);
      calculateGold();
      if (!silent) showToast('تم عرض آخر أسعار عملات محفوظة');
    } else if (!silent) showToast('تعذر تحميل أسعار العملات');
  } finally {
    $('moneyRefreshBtn')?.classList.remove('loading');
    setFooter(navigator.onLine ? 'متصل' : 'دون إنترنت');
  }
}

function renderFX(fx, cached = false, cachedAt = null) {
  $('usdEgp').textContent = formatNumber(fx.rates.USD, 2);
  $('fxSourceBadge').textContent = cached ? tr('بيانات محفوظة','Cached data') : (fx.source === 'CBE' ? tr('البنك المركزي المصري','Central Bank of Egypt') : tr('سعر مرجعي مجمّع','Blended reference rate'));
  $('fxUpdated').textContent = `${tr('آخر تحديث','Last update')}: ${cached ? formatDateTime(cachedAt) : (fx.date || formatDateTime())}`;
  $('ratesDate').textContent = fx.date || (cached ? tr('محفوظ','Cached') : tr('اليوم','Today'));
  const host = $('currencyRates');
  host.innerHTML = '';
  FX_CODES.forEach(code => {
    if (!fx.rates[code]) return;
    const el = document.createElement('div');
    el.className = 'rate-item';
    el.innerHTML = `<div class="rate-name"><span class="rate-code">${code}</span><span>${currencyName(code)}</span></div><strong>${formatNumber(fx.rates[code], code === 'KWD' ? 2 : 3)} ${tr('ج.م','EGP')}</strong>`;
    host.appendChild(el);
  });
  populateCurrencySelects();
  convertCurrency();
}

function populateCurrencySelects() {
  const selects = [$('convertFrom'), $('convertTo')];
  selects.forEach(select => {
    const previous = select.value;
    select.innerHTML = '';
    Object.keys(CURRENCIES).forEach(code => {
      if (code !== 'EGP' && !state.fx.rates[code]) return;
      const option = document.createElement('option');
      option.value = code; option.textContent = `${code} — ${currencyName(code)}`;
      select.appendChild(option);
    });
    if (previous && [...select.options].some(o => o.value === previous)) select.value = previous;
  });
  if (!$('convertFrom').dataset.initialized) {
    $('convertFrom').value = 'USD'; $('convertTo').value = 'EGP';
    $('convertFrom').dataset.initialized = '1';
  }
}
function convertCurrency() {
  const amount = Number($('convertAmount').value || 0);
  const from = $('convertFrom').value;
  const to = $('convertTo').value;
  const fromInEgp = state.fx.rates[from];
  const toInEgp = state.fx.rates[to];
  if (!Number.isFinite(fromInEgp) || !Number.isFinite(toInEgp)) { $('convertResult').textContent = tr('بيانات السعر غير متاحة','Rate data unavailable'); return; }
  const result = amount * fromInEgp / toInEgp;
  $('convertResult').textContent = `${formatNumber(amount, 2)} ${from} = ${formatNumber(result, 2)} ${to}`;
}

async function refreshGold(silent = false) {
  setFooter('تحديث الذهب…');
  try {
    const data = await fetchJSON(API.gold);
    const price = Number(data?.price ?? data?.price_gram_24k ?? data?.ask);
    if (!Number.isFinite(price) || price <= 0) throw new Error('سعر الذهب غير صالح');
    const previousCache = loadCache(CACHE_KEYS.gold);
    state.gold = {
      ounceUsd: price,
      timestamp: data?.updatedAt || data?.updated_at || data?.timestamp || nowStamp(),
      previousOunceUsd: previousCache?.data?.ounceUsd || null
    };
    saveCache(CACHE_KEYS.gold, state.gold);
    calculateGold(false);
    if (!silent) showToast('تم تحديث سعر الذهب');
  } catch (error) {
    const cached = loadCache(CACHE_KEYS.gold);
    if (cached?.data?.ounceUsd) {
      state.gold = cached.data;
      calculateGold(true, cached.savedAt);
      if (!silent) showToast('تم عرض آخر سعر ذهب محفوظ');
    } else {
      $('gold21').textContent = '—';
      if (!silent) showToast('تعذر تحميل سعر الذهب');
    }
  } finally { setFooter(navigator.onLine ? 'متصل' : 'دون إنترنت'); }
}

function goldPricePerGram(karat, includePremium = false) {
  if (!state.gold?.ounceUsd || !state.fx.rates.USD) return NaN;
  const pureGramEgp = state.gold.ounceUsd / 31.1034768 * state.fx.rates.USD;
  let value = pureGramEgp * (Number(karat) / 24);
  if (includePremium) value *= 1 + (Number(state.settings.goldPremium || 0) / 100);
  return value;
}
function calculateGold(cached = false, cachedAt = null) {
  if (!state.gold?.ounceUsd || !state.fx.rates.USD) return;
  const p21 = goldPricePerGram(21, true);
  $('gold21').textContent = formatNumber(p21, 0);
  if ($('homeGold21')) $('homeGold21').textContent = formatNumber(p21, 0);
  const ounceEgp = Number(state.gold.ounceUsd) * Number(state.fx.rates.USD);
  if ($('goldOunce')) $('goldOunce').textContent = `$${formatNumber(state.gold.ounceUsd, 2)}`;
  if ($('goldOunceEgp')) $('goldOunceEgp').textContent = `${formatNumber(ounceEgp, 0)} ${tr('ج.م','EGP')}`;
  if ($('homeGoldOunceUsd')) $('homeGoldOunceUsd').textContent = formatNumber(state.gold.ounceUsd, 2);
  if ($('homeGoldOunceEgp')) $('homeGoldOunceEgp').textContent = `${formatNumber(ounceEgp, 0)} ${tr('ج.م','EGP')}`;
  if ($('homeGoldOunceUpdated')) $('homeGoldOunceUpdated').textContent = `${tr('آخر تحديث','Last update')}: ${cached ? formatDateTime(cachedAt) : formatDateTime(state.gold.timestamp)}`;
  $('goldUpdated').textContent = `${tr('آخر تحديث','Last update')}: ${cached ? formatDateTime(cachedAt) : formatDateTime(state.gold.timestamp)}`;
  const old = Number(state.gold.previousOunceUsd);
  const changeEl = $('goldChange');
  if (old > 0) {
    const old21 = old / 31.1034768 * state.fx.rates.USD * (21 / 24) * (1 + Number(state.settings.goldPremium || 0) / 100);
    const diff = p21 - old21;
    const pct = old21 ? diff / old21 * 100 : 0;
    changeEl.textContent = `${diff >= 0 ? '▲' : '▼'} ${formatNumber(Math.abs(diff), 0)} ${tr('ج.م','EGP')} (${formatNumber(Math.abs(pct), 2)}%)`;
    changeEl.className = `change ${diff > 0 ? 'up' : diff < 0 ? 'down' : 'neutral'}`;
  } else { changeEl.textContent = tr('سيظهر التغير عند التحديث التالي','Change will appear after the next update'); changeEl.className = 'change neutral'; }
  const host = $('goldKarats');
  host.innerHTML = '';
  [24, 22, 21, 18].forEach(k => {
    const el = document.createElement('div');
    el.className = 'gold-item';
    el.innerHTML = `<span>${tr('عيار','Karat')} ${k}</span><strong>${formatNumber(goldPricePerGram(k, true), 0)} ${tr('ج.م','EGP')}</strong>`;
    host.appendChild(el);
  });
  calculateGoldValue();
}
function calculateGoldValue() {
  const weight = Number($('goldWeight').value || 0);
  const karat = Number($('goldKaratSelect').value || 21);
  const value = weight * goldPricePerGram(karat, true);
  $('goldCalcResult').textContent = Number.isFinite(value) ? `${formatNumber(value, 2)} ${tr('ج.م','EGP')}` : tr('السعر غير متاح','Price unavailable');
}

async function refreshPrayer(silent = false) {
  $('prayerRefreshBtn')?.classList.add('loading');
  setFooter('تحديث مواقيت الصلاة…');
  try {
    const payload = await fetchJSON(API.prayer);
    const data = payload?.data;
    if (!data?.timings) throw new Error('بيانات الصلاة غير مكتملة');
    state.prayer = data;
    saveCache(CACHE_KEYS.prayer, data);
    renderPrayer(data, false);
    if (!silent) showToast('تم تحديث مواقيت الصلاة');
  } catch (error) {
    const cached = loadCache(CACHE_KEYS.prayer);
    if (cached?.data?.timings) {
      state.prayer = cached.data;
      renderPrayer(cached.data, true, cached.savedAt);
      if (!silent) showToast('تم عرض آخر مواقيت محفوظة');
    } else if (!silent) showToast('تعذر تحميل مواقيت الصلاة');
  } finally {
    $('prayerRefreshBtn')?.classList.remove('loading');
    setFooter(navigator.onLine ? 'متصل' : 'دون إنترنت');
  }
}
function cleanPrayerTime(value) { return String(value || '').match(/\d{1,2}:\d{2}/)?.[0] || '--:--'; }
function adjustedPrayerTime(key) {
  const clean = cleanPrayerTime(state.prayer?.timings?.[key]);
  if (clean === '--:--') return clean;
  const [h, m] = clean.split(':').map(Number);
  const offset = Number(state.settings.prayerOffsets?.[key] || 0);
  const total = ((h * 60 + m + offset) % 1440 + 1440) % 1440;
  return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`;
}
function parsePrayerDate(key, addDay = 0) {
  return cairoDateAtTime(adjustedPrayerTime(key), addDay);
}
function nextPrayerInfo() {
  if (!state.prayer?.timings) return null;
  const now = new Date();
  for (const [key, ar] of SALAH_KEYS) {
    const d = parsePrayerDate(key);
    if (d > now) return { key, ar: prayerName(key), date: d, time: adjustedPrayerTime(key) };
  }
  const [key, ar] = SALAH_KEYS[0];
  return { key, ar: prayerName(key), date: parsePrayerDate(key, 1), time: adjustedPrayerTime(key) };
}
function renderPrayer(data, cached = false, cachedAt = null) {
  const host = $('prayerCards');
  host.innerHTML = '';
  const next = nextPrayerInfo();
  PRAYERS.forEach(([key, ar]) => {
    const el = document.createElement('div');
    el.className = `prayer-card ${next?.key === key ? 'active' : ''}`;
    const shown = key === 'Sunrise' ? cleanPrayerTime(data.timings[key]) : adjustedPrayerTime(key);
    el.innerHTML = `<span>${prayerName(key)}</span><strong>${shown}</strong>`;
    host.appendChild(el);
  });
  const hijri = data?.date?.hijri;
  if (hijri) $('hijriDate').textContent = currentLang()==='en' ? `${hijri.day} ${hijri.month?.en || ''} ${hijri.year} AH` : `${hijri.day} ${hijri.month?.ar || hijri.month?.en || ''} ${hijri.year} هـ`;
  $('prayerUpdated').textContent = cached ? `${tr('بيانات محفوظة','Cached data')}: ${formatDateTime(cachedAt)}` : tr('طريقة الحساب: الهيئة المصرية للمساحة','Calculation method: Egyptian General Authority of Survey');
  updatePrayerCountdown();
  updatePrayerTimeline();
  updateDynamicScene();
  syncHomeSummary();
  scheduleNativePrayers();
}
function prayerSchedulePayload() {
  if (!state.prayer?.timings) return [];
  return SALAH_KEYS.map(([key, ar]) => ({
    key, name: prayerName(key), time: adjustedPrayerTime(key), epochMs: parsePrayerDate(key).getTime()
  }));
}
function scheduleNativePrayers() {
  if (!hasNativeBridge() || !state.prayer?.timings) return;
  const payload = {
    date: cairoDateKey(),
    prayers: prayerSchedulePayload(),
    preAlertMinutes: Number(state.settings.prayerAlertMinutes || 0),
    notificationsEnabled: Boolean(state.settings.prayerAlert),
    playAdhan: Boolean(state.settings.adhanPlayback),
    offsets: { ...state.settings.prayerOffsets }
  };
  const result = parseNativeJson(nativeCall('schedulePrayers', JSON.stringify(payload)), {});
  if (result.ok) {
    $('prayerBackgroundStatus').textContent = `${tr('مجدولة','Scheduled')}: ${result.count || 0}`;
    $('prayerBackgroundStatus').classList.add('native-ready');
  }
}
function updatePrayerCountdown() {
  const next = nextPrayerInfo();
  if (!next) return;
  const diff = Math.max(0, next.date.getTime() - Date.now());
  $('nextPrayerName').textContent = next.ar;
  $('nextPrayerTime').textContent = next.time;
  const remaining = formatDuration(diff, false);
  $('prayerCountdown').textContent = remaining;
  if ($('homePrayerCountdown')) $('homePrayerCountdown').textContent = remaining;
  if ($('widgetPreviewRemaining')) $('widgetPreviewRemaining').textContent = remaining.slice(0,5);
  document.querySelectorAll('.prayer-card').forEach((el, i) => {
    const key = PRAYERS[i]?.[0]; el.classList.toggle('active', key === next.key);
  });
  checkPrayerAlert(next, diff);
}
async function checkPrayerAlert(next, diff) {
  if (hasNativeBridge()) return;
  const beforeMinutes = Number(state.settings.prayerAlertMinutes || 0);
  const preTarget = beforeMinutes * 60000;
  const nearPreAlert = state.settings.prayerAlert && beforeMinutes > 0 && Math.abs(diff - preTarget) <= 30000;
  const atPrayer = state.settings.adhanPlayback && diff <= 1000;
  if (!nearPreAlert && !atPrayer) return;
  const type = atPrayer ? 'adhan' : `before-${beforeMinutes}`;
  const key = `${cairoDateKey()}-${next.key}-${type}`;
  if (state.prayerAlerted.has(key)) return;
  state.prayerAlerted.add(key);
  if (atPrayer) {
    playWebAdhan().catch(() => $('alertAudio').play().catch(() => {}));
    showToast(currentLang()==='en' ? `It is time for ${next.ar}` : `حان الآن موعد صلاة ${next.ar}`, 8000);
  } else {
    $('alertAudio').play().catch(() => {});
    showToast(currentLang()==='en' ? `${next.ar} is approaching — ${beforeMinutes} minutes remaining` : `اقتربت صلاة ${next.ar} — متبقي ${beforeMinutes} دقائق`, 6000);
  }
  if ('Notification' in window && Notification.permission === 'granted') {
    new Notification(atPrayer ? (currentLang()==='en' ? `Time for ${next.ar}` : `حان وقت صلاة ${next.ar}`) : (currentLang()==='en' ? `${next.ar} prayer` : `صلاة ${next.ar}`), {
      body: atPrayer ? tr('حي على الصلاة','Come to prayer') : (currentLang()==='en' ? `${beforeMinutes} minutes remaining` : `متبقي ${beforeMinutes} دقائق`),
      icon: 'icons/icon-192.png'
    });
  }
}

function buildClockMarks(numeralStyle = 'western') {
  const host = $('clockMarks'); if (!host) return; host.innerHTML = '';
  const western = ['12','1','2','3','4','5','6','7','8','9','10','11'];
  const eastern = ['١٢','١','٢','٣','٤','٥','٦','٧','٨','٩','١٠','١١'];
  const romans = ['XII','I','II','III','IV','V','VI','VII','VIII','IX','X','XI'];
  const values = numeralStyle === 'eastern' ? eastern : numeralStyle === 'roman' ? romans : western;
  for (let i = 0; i < 12; i++) {
    const mark = document.createElement('div'); mark.className = 'clock-mark'; mark.style.setProperty('--i', i);
    const text = numeralStyle === 'markers' ? (i % 3 === 0 ? '◆' : '•') : values[i];
    mark.innerHTML = `<span>${text}</span>`; host.appendChild(mark);
  }
}
function updateClock() {
  const now = new Date();
  const parts = new Intl.DateTimeFormat('en-US', { timeZone: CAIRO.timezone, hour:'numeric', minute:'numeric', second:'numeric', hour12:false }).formatToParts(now);
  const get = type => Number(parts.find(p => p.type === type)?.value || 0);
  const h = get('hour') % 12, m = get('minute'), s = get('second'), ms = now.getMilliseconds();
  if ($('hourHand')) $('hourHand').style.transform = `translateX(-50%) rotate(${h * 30 + m * .5}deg)`;
  if ($('minuteHand')) $('minuteHand').style.transform = `translateX(-50%) rotate(${m * 6 + s * .1}deg)`;
  if ($('secondHand')) { $('secondHand').style.transform = `translateX(-50%) rotate(${s * 6 + ms * .006}deg)`; $('secondHand').style.display = state.settings.showSeconds ? '' : 'none'; }
  if ($('digitalTime')) $('digitalTime').textContent = formatTime(now, state.settings.showSeconds);
  const gregorian = new Intl.DateTimeFormat(appLocale(), { timeZone: CAIRO.timezone, weekday:'long', day:'numeric', month:'long', year:'numeric' }).format(now);
  if ($('gregorianDate')) $('gregorianDate').textContent = gregorian;
  if ($('headerDate')) $('headerDate').textContent = gregorian;
  if (!state.prayer?.date?.hijri && $('hijriDate')) {
    try { $('hijriDate').textContent = new Intl.DateTimeFormat(currentLang()==='en'?'en-GB-u-ca-islamic':'ar-EG-u-ca-islamic', { timeZone:CAIRO.timezone, day:'numeric', month:'long', year:'numeric' }).format(now); } catch (_) {}
  }
  const minuteKey = `${cairoDateKey(now)}-${get('hour')}-${m}`;
  if (state.ui.minuteKey !== minuteKey) {
    state.ui.minuteKey = minuteKey;
    updateDynamicScene();
    updatePrayerTimeline();
  }
}

function updateConnectionStatus() {
  const online = navigator.onLine;
  const badge = $('connectionBadge');
  if (badge) { badge.className = `status-pill ${online ? 'online' : 'offline'}`; badge.innerHTML = `<span class="dot"></span>${online ? tr('متصل بالإنترنت','Online') : tr('غير متصل','Offline')}`; }
  setFooter(online ? tr('متصل','Online') : tr('دون إنترنت','Offline'));
  const connection = navigator.connection || navigator.mozConnection || navigator.webkitConnection;
  if ($('networkType')) $('networkType').textContent = connection?.effectiveType?.toUpperCase() || connection?.type || (online ? tr('متصل','Connected') : tr('غير متصل','Offline'));
  if ($('networkEstimate')) $('networkEstimate').textContent = Number.isFinite(connection?.downlink) ? `${connection.downlink} Mbps` : tr('غير متاح','Unavailable');
}
async function initBattery() {
  if (!navigator.getBattery) { $('batteryText').textContent = tr('غير مدعوم','Not supported'); return; }
  try {
    const battery = await navigator.getBattery();
    const render = () => {
      const pct = Math.round(battery.level * 100);
      $('batteryText').textContent = `${pct}%${battery.charging ? ' ⚡' : ''}`;
      $('batteryFill').style.width = `${pct}%`;
      $('batteryFill').style.background = pct < 20 ? 'var(--danger)' : pct < 45 ? 'var(--warning)' : 'var(--success)';
    };
    render(); battery.addEventListener('levelchange', render); battery.addEventListener('chargingchange', render);
  } catch (_) { $('batteryText').textContent = tr('غير متاح','Unavailable'); }
}

function loadSpeedHistory() {
  try { state.speedHistory = JSON.parse(localStorage.getItem(SPEED_HISTORY_KEY) || '[]'); }
  catch (_) { state.speedHistory = []; }
  renderSpeedHistory();
}
function saveSpeedHistory(entry) {
  state.speedHistory.unshift(entry);
  state.speedHistory = state.speedHistory.slice(0, 6);
  localStorage.setItem(SPEED_HISTORY_KEY, JSON.stringify(state.speedHistory));
  renderSpeedHistory();
}
function renderSpeedHistory() {
  const host = $('speedHistory');
  if (!host) return;
  host.innerHTML = state.speedHistory.map(row => `
    <div class="speed-history-row">
      <span>${formatDateTime(row.at)}</span>
      <span>↓ ${formatNumber(row.download, 1)}</span>
      <span>↑ ${formatNumber(row.upload, 1)}</span>
      <span>${formatNumber(row.latency, 0)} ms</span>
      <span>J ${formatNumber(row.jitter, 0)}</span>
    </div>`).join('');
}
async function timedPing(index) {
  const started = performance.now();
  const response = await fetch(`https://speed.cloudflare.com/__down?bytes=1&nonce=${Date.now()}-${index}`, {
    cache: 'no-store', mode: 'cors'
  });
  if (!response.ok) throw new Error(`Ping HTTP ${response.status}`);
  await response.arrayBuffer();
  return performance.now() - started;
}
async function runSpeedTest() {
  const buttons = [$('speedTestBtn'), $('quickSpeedBtn')].filter(Boolean);
  buttons.forEach(b => { b.disabled = true; b.classList.add('loading'); });
  $('speedQuality').textContent = tr('يعمل…','Running…');
  ['downloadSpeed','uploadSpeed','latencyValue','jitterValue','packetSuccess'].forEach(id => { if ($(id)) $(id).textContent = '—'; });
  try {
    const latencySamples = [];
    let pingSuccess = 0;
    for (let i = 0; i < 6; i++) {
      try { latencySamples.push(await timedPing(i)); pingSuccess++; }
      catch (_) { /* counted as failed request */ }
    }
    if (!latencySamples.length) throw new Error('لا يوجد رد من خادم الاختبار');
    const latency = latencySamples.reduce((a, b) => a + b, 0) / latencySamples.length;
    const jitter = latencySamples.length > 1
      ? latencySamples.slice(1).reduce((sum, value, i) => sum + Math.abs(value - latencySamples[i]), 0) / (latencySamples.length - 1)
      : 0;

    const downloadBytes = 5_000_000;
    const downloadStart = performance.now();
    const downloadResponse = await fetch(`https://speed.cloudflare.com/__down?bytes=${downloadBytes}&nonce=${Date.now()}`, {
      cache: 'no-store', mode: 'cors'
    });
    if (!downloadResponse.ok) throw new Error(`Download HTTP ${downloadResponse.status}`);
    const buffer = await downloadResponse.arrayBuffer();
    const downloadSeconds = (performance.now() - downloadStart) / 1000;
    const downloadMbps = (buffer.byteLength * 8) / downloadSeconds / 1_000_000;

    const uploadBytes = 1_500_000;
    const uploadPayload = new Uint8Array(uploadBytes);
    crypto.getRandomValues(uploadPayload.subarray(0, Math.min(uploadPayload.length, 65536)));
    const uploadStart = performance.now();
    const uploadResponse = await fetch(`https://speed.cloudflare.com/__up?nonce=${Date.now()}`, {
      method: 'POST', body: uploadPayload, cache: 'no-store', mode: 'cors',
      headers: { 'Content-Type': 'application/octet-stream' }
    });
    if (!uploadResponse.ok) throw new Error(`Upload HTTP ${uploadResponse.status}`);
    const uploadSeconds = (performance.now() - uploadStart) / 1000;
    const uploadMbps = (uploadBytes * 8) / uploadSeconds / 1_000_000;
    const successPct = pingSuccess / 6 * 100;

    $('downloadSpeed').textContent = `${formatNumber(downloadMbps, 2)} Mbps`;
    $('uploadSpeed').textContent = `${formatNumber(uploadMbps, 2)} Mbps`;
    $('latencyValue').textContent = `${formatNumber(latency, 0)} ms`;
    $('jitterValue').textContent = `${formatNumber(jitter, 0)} ms`;
    $('packetSuccess').textContent = `${formatNumber(successPct, 0)}%`;
    const quality = downloadMbps < 5 || latency > 180 ? tr('ضعيف','Poor')
      : downloadMbps < 20 || latency > 90 ? tr('متوسط','Average')
      : downloadMbps < 60 || latency > 45 ? tr('جيد','Good') : tr('ممتاز','Excellent');
    $('speedQuality').textContent = quality;
    $('speedNeedle').style.transform = `rotate(${clamp(downloadMbps / 100 * 180, 0, 180)}deg)`;
    saveSpeedHistory({ at: nowStamp(), download: downloadMbps, upload: uploadMbps, latency, jitter, successPct });
  } catch (error) {
    const connection = navigator.connection || navigator.mozConnection || navigator.webkitConnection;
    if (Number.isFinite(connection?.downlink)) {
      $('downloadSpeed').textContent = `${connection.downlink} Mbps`;
      $('speedQuality').textContent = tr('تقدير الجهاز','Device estimate');
      $('speedNote').textContent = tr('تعذر الاختبار الكامل؛ تم عرض تقدير المتصفح للتنزيل.','Full test failed; showing the browser download estimate.');
    } else {
      $('speedQuality').textContent = tr('تعذر الاختبار','Test failed');
      showToast('تعذر اختبار السرعة من هذا الاتصال أو المتصفح');
    }
  } finally {
    buttons.forEach(b => { b.disabled = false; b.classList.remove('loading'); });
  }
}

function formatDuration(ms, withHundredths = false) {
  ms = Math.max(0, ms);
  const totalSeconds = Math.floor(ms / 1000);
  const h = Math.floor(totalSeconds / 3600);
  const m = Math.floor((totalSeconds % 3600) / 60);
  const s = totalSeconds % 60;
  const base = [h,m,s].map(n => String(n).padStart(2,'0')).join(':');
  return withHundredths ? `${base}.${String(Math.floor((ms % 1000) / 10)).padStart(2,'0')}` : base;
}
function readTimerInputs() {
  const h = clamp(Number($('timerHours').value || 0), 0, 99);
  const m = clamp(Number($('timerMinutes').value || 0), 0, 59);
  const s = clamp(Number($('timerSeconds').value || 0), 0, 59);
  return (h * 3600 + m * 60 + s) * 1000;
}
function syncTimerInputs(ms) {
  const total = Math.floor(ms / 1000);
  $('timerHours').value = Math.floor(total / 3600);
  $('timerMinutes').value = Math.floor((total % 3600) / 60);
  $('timerSeconds').value = total % 60;
  $('timerDisplay').textContent = formatDuration(ms);
}
function saveTimerState() {
  localStorage.setItem(TIMER_KEY, JSON.stringify({
    running: state.timer.running, endAt: state.timer.endAt, remainingMs: state.timer.remainingMs
  }));
}
function loadTimerState() {
  try {
    const saved = JSON.parse(localStorage.getItem(TIMER_KEY) || '{}');
    if (saved.running && Number(saved.endAt) > Date.now()) {
      state.timer.running = true;
      state.timer.endAt = Number(saved.endAt);
      state.timer.remainingMs = state.timer.endAt - Date.now();
      tickTimer();
    } else if (Number.isFinite(Number(saved.remainingMs))) {
      state.timer.remainingMs = Math.max(0, Number(saved.remainingMs));
      syncTimerInputs(state.timer.remainingMs);
    }
  } catch (_) { /* ignore */ }
}
function tickTimer() {
  if (!state.timer.running) return;
  state.timer.remainingMs = Math.max(0, state.timer.endAt - Date.now());
  $('timerDisplay').textContent = formatDuration(state.timer.remainingMs);
  if (state.timer.remainingMs <= 0) {
    state.timer.running = false;
    saveTimerState();
    if (!hasNativeBridge()) {
      $('alertAudio').play().catch(()=>{});
      if (navigator.vibrate) navigator.vibrate([250,150,250]);
    }
    showToast('انتهى المؤقت', 5000);
    return;
  }
  state.timer.raf = requestAnimationFrame(tickTimer);
}
function startTimer() {
  if (state.timer.running) return;
  if (state.timer.remainingMs <= 0 || state.timer.remainingMs === 300000) state.timer.remainingMs = readTimerInputs();
  if (state.timer.remainingMs <= 0) return showToast('حدد مدة للمؤقت أولًا');
  state.timer.running = true;
  state.timer.endAt = Date.now() + state.timer.remainingMs;
  saveTimerState();
  nativeCall('scheduleTimer', String(state.timer.endAt), 'انتهى المؤقت');
  tickTimer();
}
function pauseTimer() {
  if (!state.timer.running) return;
  state.timer.remainingMs = Math.max(0, state.timer.endAt - Date.now());
  state.timer.running = false;
  cancelAnimationFrame(state.timer.raf);
  nativeCall('cancelTimer');
  syncTimerInputs(state.timer.remainingMs);
  saveTimerState();
}
function resetTimer() {
  state.timer.running = false;
  cancelAnimationFrame(state.timer.raf);
  nativeCall('cancelTimer');
  state.timer.remainingMs = readTimerInputs();
  syncTimerInputs(state.timer.remainingMs);
  saveTimerState();
}

function saveStopwatchState() {
  localStorage.setItem(STOPWATCH_KEY, JSON.stringify({
    running: state.stopwatch.running,
    startedEpoch: state.stopwatch.startedEpoch,
    elapsedMs: state.stopwatch.elapsedMs,
    laps: state.stopwatch.laps
  }));
}
function loadStopwatchState() {
  try {
    const saved = JSON.parse(localStorage.getItem(STOPWATCH_KEY) || '{}');
    state.stopwatch.running = Boolean(saved.running);
    state.stopwatch.startedEpoch = Number(saved.startedEpoch || 0);
    state.stopwatch.elapsedMs = Number(saved.elapsedMs || 0);
    state.stopwatch.laps = Array.isArray(saved.laps) ? saved.laps : [];
    if (state.stopwatch.running && !state.stopwatch.startedEpoch) {
      state.stopwatch.running = false;
    }
    $('stopwatchStart').textContent = state.stopwatch.running ? tr('إيقاف','Stop') : state.stopwatch.elapsedMs ? tr('استكمال','Resume') : tr('بدء','Start');
    renderLaps();
    tickStopwatch();
  } catch (_) { /* ignore */ }
}
function renderStopwatch() {
  if (!$('stopwatchStart')) return;
  $('stopwatchStart').textContent = state.stopwatch.running ? tr('إيقاف','Stop') : state.stopwatch.elapsedMs ? tr('استكمال','Resume') : tr('بدء','Start');
  renderLaps();
}
function tickStopwatch() {
  const total = stopwatchTotal();
  $('stopwatchDisplay').textContent = formatDuration(total, true);
  if (!state.stopwatch.running) return;
  state.stopwatch.raf = requestAnimationFrame(tickStopwatch);
}
function toggleStopwatch() {
  if (state.stopwatch.running) {
    state.stopwatch.elapsedMs += Date.now() - state.stopwatch.startedEpoch;
    state.stopwatch.running = false;
    state.stopwatch.startedEpoch = 0;
    cancelAnimationFrame(state.stopwatch.raf);
    $('stopwatchStart').textContent = tr('استكمال','Resume');
  } else {
    state.stopwatch.startedEpoch = Date.now();
    state.stopwatch.running = true;
    $('stopwatchStart').textContent = tr('إيقاف','Stop');
    tickStopwatch();
  }
  saveStopwatchState();
}
function stopwatchTotal() {
  return state.stopwatch.elapsedMs + (state.stopwatch.running ? Date.now() - state.stopwatch.startedEpoch : 0);
}
function addLap() {
  const total = stopwatchTotal();
  if (total <= 0) return;
  state.stopwatch.laps.unshift(total);
  saveStopwatchState();
  renderLaps();
}
function renderLaps() {
  $('lapList').innerHTML = state.stopwatch.laps.map((ms,i)=>`<li>${tr('لفة','Lap')} ${state.stopwatch.laps.length-i}: ${formatDuration(ms,true)}</li>`).join('');
}
function resetStopwatch() {
  state.stopwatch.running = false;
  cancelAnimationFrame(state.stopwatch.raf);
  state.stopwatch.startedEpoch = 0;
  state.stopwatch.elapsedMs = 0;
  state.stopwatch.laps = [];
  $('stopwatchDisplay').textContent = '00:00:00.00';
  $('stopwatchStart').textContent = tr('بدء','Start');
  saveStopwatchState();
  renderLaps();
}

function safeCalculate(expression) {
  let exp = String(expression || '')
    .replace(/×/g,'*')
    .replace(/÷/g,'/')
    .replace(/−/g,'-')
    .replace(/(\d+(?:\.\d+)?)%/g,'($1/100)')
    .replace(/\^/g,'**')
    .replace(/(^|[+\-*/(\s])0+(?=\d)/g,'$1');
  if (!/^[0-9+\-*/().\s]+$/.test(exp)) throw new Error('عملية غير صالحة');
  const value = Function(`"use strict"; return (${exp})`)();
  if (!Number.isFinite(value)) throw new Error('لا يمكن القسمة على صفر');
  return value;
}
function calcInput(value) {
  const operators = '+-*/^';
  const current = state.calc.expression;
  const last = current.slice(-1);
  if (operators.includes(value) && operators.includes(last)) {
    state.calc.expression = current.slice(0,-1) + value;
    renderCalc();
    return;
  }
  if (value === '(' && /[0-9.)]/.test(last)) state.calc.expression += '*';
  if ((/^[0-9]/.test(value) || value === '.') && last === ')') state.calc.expression += '*';
  state.calc.expression += value;
  renderCalc();
}
function renderCalc() { $('calcExpression').textContent = state.calc.expression || '0'; }
function calcEquals() {
  if (!state.calc.expression) return;
  try {
    const value = safeCalculate(state.calc.expression);
    const shown = Number(value.toFixed(10)).toString();
    state.calc.history.unshift(`${state.calc.expression} = ${shown}`); state.calc.history = state.calc.history.slice(0,8);
    localStorage.setItem(CALC_KEY, JSON.stringify(state.calc.history));
    $('calcResult').textContent = shown; state.calc.expression = shown; renderCalc(); renderCalcHistory();
  } catch (e) { $('calcResult').textContent = tr('خطأ','Error'); showToast(e.message); }
}
function loadCalcHistory() {
  try { state.calc.history = JSON.parse(localStorage.getItem(CALC_KEY) || '[]'); }
  catch (_) { state.calc.history = []; }
  renderCalcHistory();
}
function renderCalcHistory() { $('calcHistory').innerHTML = state.calc.history.map(x=>`<div>${x}</div>`).join(''); }

function loadPedometer() {
  const today = cairoDateKey();
  try {
    const saved = JSON.parse(localStorage.getItem('cairoPulse.steps.v2') || '{}');
    state.pedometer.date = today;
    state.pedometer.steps = saved.date === today ? Number(saved.steps || 0) : 0;
  } catch (_) {
    state.pedometer.date = today;
    state.pedometer.steps = 0;
  }
  renderSteps();
  refreshNativeStatus();
}
function saveSteps() {
  localStorage.setItem('cairoPulse.steps.v2', JSON.stringify({
    date: cairoDateKey(), steps: state.pedometer.steps
  }));
}
function renderSteps() {
  const steps = state.pedometer.steps;
  const goal = Math.max(100, Number(state.settings.stepGoal || 6000));
  const pct = clamp(steps / goal * 100, 0, 100);
  $('stepCount').textContent = new Intl.NumberFormat(appLocale()).format(steps);
  $('stepDistance').textContent = `${formatNumber(steps * 0.00075, 2)} ${tr('كم','km')}`;
  $('stepCalories').textContent = formatNumber(steps * 0.04, 0);
  $('stepGoalText').textContent = `${formatNumber(pct, 0)}%`;
  document.querySelector('.steps-circle')?.style.setProperty('--step-progress', `${pct}%`);
}
function applyNativeStatus(status) {
  state.native.available = Boolean(status.native);
  if (!state.native.available) return;
  $('nativeModeBadge').textContent = tr('Android أصلي','Native Android');
  $('nativeModeBadge').classList.add('native-ready');
  if (Number.isFinite(Number(status.steps))) {
    state.pedometer.steps = Number(status.steps);
    state.pedometer.native = true;
    state.pedometer.active = Boolean(status.stepServiceRunning);
    renderSteps();
  }
  $('stepStatus').textContent = status.stepServiceRunning ? tr('يعمل بالخلفية','Running in background') : (status.stepSensorUnavailable ? tr('لا يوجد حساس متاح','No sensor available') : tr('متوقف','Stopped'));
  $('stepStart').textContent = status.stepServiceRunning ? tr('إيقاف العداد','Stop counter') : tr('تشغيل العداد','Start counter');
  $('stepsBackgroundStatus').textContent = status.stepServiceRunning ? `${tr('يعمل','Running')}: ${status.stepSensor || tr('حساس الجهاز','Device sensor')}` : (status.stepSensorUnavailable ? tr('الحساس غير متاح','Sensor unavailable') : tr('متوقف','Stopped'));
  $('stepsBackgroundStatus').classList.toggle('native-ready', Boolean(status.stepServiceRunning));
  $('exactAlarmStatus').textContent = status.exactAlarmAllowed ? tr('مسموح','Allowed') : tr('يحتاج موافقة','Permission required');
  $('dataBackgroundStatus').textContent = status.lastBackgroundError
    ? status.lastBackgroundError
    : (status.backgroundUpdatesEnabled ? tr('مفعّل دوريًا','Enabled periodically') : tr('غير مفعّل','Disabled'));
  $('backgroundLastUpdate').textContent = Number(status.lastBackgroundUpdate) > 0
    ? formatDateTime(new Date(Number(status.lastBackgroundUpdate))) : tr('لم يحدث بعد','Not updated yet');
  $('prayerBackgroundStatus').textContent = status.prayerOccurrences ? `${tr('مجدولة','Scheduled')}: ${status.prayerOccurrences} ${tr('موعد','occurrences')}` : (status.prayersScheduled ? `${tr('مجدولة','Scheduled')}: ${status.prayersScheduled} ${tr('تنبيه','alerts')}` : tr('غير مجدولة','Not scheduled'));
  $('adhanAudioStatus').textContent = status.hasCustomAdhan
    ? `${tr('الصوت الحالي','Current sound')}: ${status.customAdhanName || tr('ملف أذان مخصص','Custom adhan file')}`
    : tr('الصوت الحالي: نغمة التطبيق الافتراضية','Current sound: default app chime');
  if (Number.isFinite(Number(status.batteryLevel))) {
    const pct = Number(status.batteryLevel);
    $('batteryText').textContent = `${pct}%${status.charging ? ' ⚡' : ''}`;
    $('batteryFill').style.width = `${pct}%`;
  }
  if (status.networkType) $('networkType').textContent = status.networkType;
}
function refreshNativeStatus() {
  if (!hasNativeBridge()) return;
  const status = parseNativeJson(nativeCall('getStatus'), {});
  applyNativeStatus(status);
}
function hydrateNativeBackgroundData() {
  if (!hasNativeBridge()) return;
  const payload = parseNativeJson(nativeCall('getBackgroundData'), {});
  const savedAt = Number(payload.lastBackgroundUpdate) > 0
    ? new Date(Number(payload.lastBackgroundUpdate)).toISOString() : nowStamp();
  if (payload.weather?.current && payload.weather?.daily) {
    state.weather = payload.weather;
    saveCache(CACHE_KEYS.weather, payload.weather);
    renderWeather(payload.weather, true, savedAt);
  }
  if (payload.fx?.rates?.USD) {
    state.fx = payload.fx;
    saveCache(CACHE_KEYS.fx, payload.fx);
    renderFX(payload.fx, true, savedAt);
  }
  if (payload.gold?.ounceUsd) {
    state.gold = payload.gold;
    saveCache(CACHE_KEYS.gold, payload.gold);
    calculateGold(true, savedAt);
  }
  if (payload.prayer?.timings) {
    state.prayer = payload.prayer;
    saveCache(CACHE_KEYS.prayer, payload.prayer);
    renderPrayer(payload.prayer, true, savedAt);
  }
}
async function startPedometer() {
  if (hasNativeBridge()) {
    if (state.pedometer.active) nativeCall('stopStepCounter');
    else { nativeCall('setStepSensitivity', state.settings.stepSensitivity); nativeCall('startStepCounter'); }
    setTimeout(refreshNativeStatus, 500);
    return;
  }
  if (state.pedometer.active) { stopPedometer(); return; }
  if (!('DeviceMotionEvent' in window)) return showToast('هذا الجهاز لا يوفر حساس حركة للمتصفح');
  try {
    if (typeof DeviceMotionEvent.requestPermission === 'function') {
      const result = await DeviceMotionEvent.requestPermission();
      if (result !== 'granted') throw new Error('لم يتم منح إذن الحركة');
    }
    const thresholds = { low: 1.65, medium: 1.15, high: 0.78 };
    state.pedometer.listener = (event) => {
      const a = event.accelerationIncludingGravity || event.acceleration;
      if (!a || [a.x,a.y,a.z].some(v => !Number.isFinite(v))) return;
      const mag = Math.sqrt(a.x*a.x + a.y*a.y + a.z*a.z);
      state.pedometer.gravity = state.pedometer.gravity * 0.88 + mag * 0.12;
      const linear = Math.abs(mag - state.pedometer.gravity);
      const threshold = thresholds[state.settings.stepSensitivity] || thresholds.medium;
      const now = performance.now();
      if (linear > threshold && !state.pedometer.above && now - state.pedometer.lastStep > 300) {
        state.pedometer.steps++;
        state.pedometer.lastStep = now;
        saveSteps();
        renderSteps();
      }
      state.pedometer.above = linear > threshold;
    };
    window.addEventListener('devicemotion', state.pedometer.listener, { passive:true });
    state.pedometer.active = true;
    $('stepStart').textContent = tr('إيقاف العداد','Stop counter');
    $('stepStatus').textContent = tr('يعمل أثناء فتح التطبيق','Runs while app is open');
    showToast('تم تشغيل عداد الخطوات');
  } catch (e) { showToast(e.message || 'تعذر تشغيل حساس الحركة'); }
}
function stopPedometer() {
  if (hasNativeBridge()) {
    nativeCall('stopStepCounter');
    refreshNativeStatus();
    return;
  }
  if (state.pedometer.listener) window.removeEventListener('devicemotion', state.pedometer.listener);
  state.pedometer.active = false;
  state.pedometer.listener = null;
  $('stepStart').textContent = tr('تشغيل العداد','Start counter');
  $('stepStatus').textContent = tr('متوقف','Stopped');
}

function applySettings() {
  const s = state.settings;
  if (!s.language) s.language = 'ar';
  if (!s.clockMode) s.clockMode = 'analog';
  if (!s.analogStyle) s.analogStyle = s.clockStyle || 'classic';
  if (!s.analogNumerals) s.analogNumerals = s.clockStyle === 'roman' ? 'roman' : 'western';
  if (!s.digitalStyle) s.digitalStyle = 'segment';
  if (!s.digitalDigits) s.digitalDigits = 'western';
  let theme = s.theme;
  if (theme === 'auto') theme = matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
  document.documentElement.dataset.theme = theme;
  document.documentElement.dataset.accent = s.accent;
  applyLanguage(false);
  if ($('themeSelect')) $('themeSelect').value = s.theme; if ($('accentSelect')) $('accentSelect').value = s.accent;
  if ($('secondsToggle')) $('secondsToggle').checked = s.showSeconds; if ($('hourFormatToggle')) $('hourFormatToggle').checked = s.hour12;
  if ($('languageSelect')) $('languageSelect').value = s.language;
  if ($('analogStyleSelect')) $('analogStyleSelect').value = s.analogStyle;
  if ($('analogNumeralsSelect')) $('analogNumeralsSelect').value = s.analogNumerals;
  if ($('digitalStyleSelect')) $('digitalStyleSelect').value = s.digitalStyle;
  if ($('digitalDigitsSelect')) $('digitalDigitsSelect').value = s.digitalDigits;
  if ($('weatherInterval')) $('weatherInterval').value = String(s.weatherInterval); if ($('moneyInterval')) $('moneyInterval').value = String(s.moneyInterval);
  if ($('prayerAlertToggle')) $('prayerAlertToggle').checked = s.prayerAlert; if ($('prayerAlertMinutes')) $('prayerAlertMinutes').value = String(s.prayerAlertMinutes);
  if ($('adhanPlaybackToggle')) $('adhanPlaybackToggle').checked = s.adhanPlayback;
  ['Fajr','Dhuhr','Asr','Maghrib','Isha'].forEach(key => { const el = $(`offset${key}`); if (el) el.value = String(s.prayerOffsets?.[key] || 0); });
  if ($('goldPremium')) $('goldPremium').value = String(s.goldPremium); if ($('stepSensitivity')) $('stepSensitivity').value = s.stepSensitivity; if ($('stepGoal')) $('stepGoal').value = String(s.stepGoal);
  const clock = $('analogClock'); if (clock) clock.className = `analog-clock ${s.analogStyle}`;
  buildClockMarks(s.analogNumerals);
  const stage = $('clockStage'); if (stage) stage.className = `clock-stage ${s.clockMode === 'digital' ? 'digital-active' : 'analog-active'}`;
  $('analogModeBtn')?.classList.toggle('active', s.clockMode !== 'digital');
  $('digitalModeBtn')?.classList.toggle('active', s.clockMode === 'digital');
  const digital = $('digitalClockPanel'); if (digital) digital.className = `digital-wrap digital-${s.digitalStyle}`;
  if ($('themeBtn')) $('themeBtn').textContent = theme === 'dark' ? '☀' : '☾';
  renderSteps(); updateClock(); updateConnectionStatus(); convertCurrency(); calculateGold(); scheduleUpdates();
  if (state.weather) renderWeather(state.weather, false);
  if (state.fx?.rates?.USD) renderFX(state.fx, false);
  if (state.gold) calculateGold(false);
  if (state.prayer) { renderPrayer(state.prayer, false); scheduleNativePrayers(); }
  updateDynamicScene(); updatePrayerTimeline(); syncHomeSummary();
  renderStopwatch(); renderSpeedHistory(); refreshNativeStatus();
}
function scheduleUpdates() {
  clearInterval(state.intervals.weather); clearInterval(state.intervals.money);
  state.intervals.weather = setInterval(() => refreshWeather(true), Number(state.settings.weatherInterval) * 60000);
  state.intervals.money = setInterval(() => { refreshFX(true); refreshGold(true); }, Number(state.settings.moneyInterval) * 60000);
}
function updateSetting(key, value) { state.settings[key] = value; saveSettings(); applySettings(); if (key === 'language') applyLanguage(true); }

async function refreshAll(silent = false) {
  $('refreshAllBtn')?.classList.add('loading');
  await Promise.allSettled([refreshWeather(true), refreshFX(true), refreshGold(true), refreshPrayer(true)]);
  $('refreshAllBtn')?.classList.remove('loading');
  if (!silent) showToast(tr('اكتمل تحديث البيانات المتاحة','Available data updated'));
}
function loadCachedData() {
  const weather = loadCache(CACHE_KEYS.weather); if (weather?.data) { state.weather=weather.data; renderWeather(weather.data,true,weather.savedAt); }
  const fx = loadCache(CACHE_KEYS.fx); if (fx?.data) { state.fx=fx.data; renderFX(fx.data,true,fx.savedAt); }
  const gold = loadCache(CACHE_KEYS.gold); if (gold?.data) { state.gold=gold.data; calculateGold(true,gold.savedAt); }
  const prayer = loadCache(CACHE_KEYS.prayer); if (prayer?.data) { state.prayer=prayer.data; renderPrayer(prayer.data,true,prayer.savedAt); }
}

function bindEvents() {
  window.addEventListener('online', () => { updateConnectionStatus(); refreshAll(true); });
  window.addEventListener('offline', updateConnectionStatus);
  const connection = navigator.connection || navigator.mozConnection || navigator.webkitConnection;
  connection?.addEventListener?.('change', updateConnectionStatus);

  $('refreshAllBtn').addEventListener('click', () => refreshAll(false));
  $('weatherRefreshBtn').addEventListener('click', () => refreshWeather(false));
  $('moneyRefreshBtn').addEventListener('click', () => Promise.all([refreshFX(false), refreshGold(true)]));
  $('prayerRefreshBtn').addEventListener('click', () => refreshPrayer(false));
  $('quickSpeedBtn').addEventListener('click', runSpeedTest); $('speedTestBtn').addEventListener('click', runSpeedTest);

  ['convertAmount','convertFrom','convertTo'].forEach(id => $(id).addEventListener('input', convertCurrency));
  $('swapCurrencies').addEventListener('click', () => { const a=$('convertFrom').value; $('convertFrom').value=$('convertTo').value; $('convertTo').value=a; convertCurrency(); });
  ['goldWeight','goldKaratSelect'].forEach(id => $(id).addEventListener('input', calculateGoldValue));
  $('goldPremium').addEventListener('input', e => { state.settings.goldPremium=Number(e.target.value||0); saveSettings(); calculateGold(); });

  $('calcKeys').addEventListener('click', e => {
    const b=e.target.closest('button'); if(!b)return;
    if (b.dataset.value) calcInput(b.dataset.value);
    else if (b.dataset.action==='clear') { state.calc.expression=''; $('calcResult').textContent='0'; renderCalc(); }
    else if (b.dataset.action==='back') { state.calc.expression=state.calc.expression.slice(0,-1); renderCalc(); }
    else if (b.dataset.action==='equals') calcEquals();
    else if (b.dataset.action==='sqrt') { try { const base=state.calc.expression ? safeCalculate(state.calc.expression) : Number($('calcResult').textContent); if(base<0)throw new Error('لا يوجد جذر حقيقي لعدد سالب'); state.calc.expression=String(Math.sqrt(base)); $('calcResult').textContent=state.calc.expression; renderCalc(); } catch(e){showToast(e.message);} }
  });
  $('calcClearHistory').addEventListener('click',()=>{state.calc.history=[];localStorage.removeItem(CALC_KEY);renderCalcHistory();});
  document.addEventListener('keydown', e => {
    if (['INPUT','SELECT','TEXTAREA'].includes(document.activeElement?.tagName)) return;
    if (/^[0-9.+\-*/%^()]$/.test(e.key)) calcInput(e.key);
    else if (e.key==='Enter') calcEquals(); else if(e.key==='Backspace'){state.calc.expression=state.calc.expression.slice(0,-1);renderCalc();} else if(e.key==='Escape'){state.calc.expression='';renderCalc();}
  });

  ['timerHours','timerMinutes','timerSeconds'].forEach(id => $(id).addEventListener('input',()=>{if(!state.timer.running){state.timer.remainingMs=readTimerInputs();$('timerDisplay').textContent=formatDuration(state.timer.remainingMs);}}));
  $('timerStart').addEventListener('click',startTimer); $('timerPause').addEventListener('click',pauseTimer); $('timerReset').addEventListener('click',resetTimer);
  document.querySelectorAll('.quick-timers button').forEach(b=>b.addEventListener('click',()=>{pauseTimer();state.timer.remainingMs=Number(b.dataset.minutes)*60000;syncTimerInputs(state.timer.remainingMs);saveTimerState();}));
  $('stopwatchStart').addEventListener('click',toggleStopwatch); $('stopwatchLap').addEventListener('click',addLap); $('stopwatchReset').addEventListener('click',resetStopwatch);
  $('stepStart').addEventListener('click',startPedometer); $('stepReset').addEventListener('click',()=>{
    if (hasNativeBridge()) nativeCall('resetSteps');
    state.pedometer.steps=0;saveSteps();renderSteps();showToast('تم تصفير خطوات اليوم');setTimeout(refreshNativeStatus,300);
  });

  $('themeBtn').addEventListener('click',()=>updateSetting('theme',document.documentElement.dataset.theme==='dark'?'light':'dark'));
  $('themeSelect').addEventListener('change',e=>updateSetting('theme',e.target.value));
  $('accentSelect').addEventListener('change',e=>updateSetting('accent',e.target.value));
  $('secondsToggle').addEventListener('change',e=>updateSetting('showSeconds',e.target.checked));
  $('hourFormatToggle').addEventListener('change',e=>updateSetting('hour12',e.target.checked));
  $('languageBtn')?.addEventListener('click',()=>updateSetting('language', currentLang()==='ar'?'en':'ar'));
  $('languageSelect')?.addEventListener('change',e=>updateSetting('language',e.target.value));
  $('analogModeBtn')?.addEventListener('click',()=>updateSetting('clockMode','analog'));
  $('digitalModeBtn')?.addEventListener('click',()=>updateSetting('clockMode','digital'));
  $('analogStyleSelect')?.addEventListener('change',e=>updateSetting('analogStyle',e.target.value));
  $('analogNumeralsSelect')?.addEventListener('change',e=>updateSetting('analogNumerals',e.target.value));
  $('digitalStyleSelect')?.addEventListener('change',e=>updateSetting('digitalStyle',e.target.value));
  $('digitalDigitsSelect')?.addEventListener('change',e=>updateSetting('digitalDigits',e.target.value));
  $('weatherInterval').addEventListener('change',e=>updateSetting('weatherInterval',Number(e.target.value)));
  $('moneyInterval').addEventListener('change',e=>updateSetting('moneyInterval',Number(e.target.value)));
  $('stepSensitivity').addEventListener('change',e=>{updateSetting('stepSensitivity',e.target.value);if(hasNativeBridge())nativeCall('setStepSensitivity',e.target.value);});
  $('stepGoal').addEventListener('input',e=>{state.settings.stepGoal=Math.max(100,Number(e.target.value||6000));saveSettings();renderSteps();});
  $('prayerAlertMinutes').addEventListener('change',e=>updateSetting('prayerAlertMinutes',Number(e.target.value)));
  $('prayerAlertToggle').addEventListener('change',async e=>{
    if (e.target.checked && hasNativeBridge()) nativeCall('requestCorePermissions');
    else if(e.target.checked && 'Notification' in window && Notification.permission==='default') await Notification.requestPermission().catch(()=>{});
    updateSetting('prayerAlert',e.target.checked);
  });
  $('adhanPlaybackToggle').addEventListener('change',e=>updateSetting('adhanPlayback',e.target.checked));
  $('chooseAdhanBtn').addEventListener('click',()=>{
    if (hasNativeBridge()) nativeCall('chooseAdhanAudio');
    else $('adhanFileInput').click();
  });
  $('adhanFileInput').addEventListener('change',async e=>{
    const file=e.target.files?.[0]; if(!file)return;
    try { await saveWebAdhanFile(file); $('adhanAudioStatus').textContent=`الصوت الحالي: ${file.name}`; showToast('تم حفظ ملف الأذان على هذا الجهاز'); }
    catch(_){ showToast('تعذر حفظ ملف الأذان'); }
  });
  $('testAdhanBtn').addEventListener('click',()=>{
    if (hasNativeBridge()) nativeCall('playAdhanTest');
    else playWebAdhan().catch(()=>showToast('تعذر تشغيل ملف الأذان'));
  });
  $('stopAdhanBtn').addEventListener('click',()=>{ if(hasNativeBridge())nativeCall('stopAdhan'); else stopWebAdhan(); });
  $('clearAdhanBtn').addEventListener('click',()=>{
    if(hasNativeBridge()){nativeCall('stopAdhan');nativeCall('clearCustomAdhan');showToast('تم حذف ملف الأذان المخصص');}
    else { clearWebAdhanFile().then(()=>{ $('adhanAudioStatus').textContent='الصوت الحالي: نغمة التطبيق الافتراضية'; showToast('تم حذف ملف الأذان المخصص'); }).catch(()=>showToast('تعذر حذف ملف الصوت')); }
  });
  $('reschedulePrayerBtn').addEventListener('click',()=>{
    const offsets={};
    ['Fajr','Dhuhr','Asr','Maghrib','Isha'].forEach(key=>offsets[key]=clamp(Number($(`offset${key}`).value||0),-30,30));
    state.settings.prayerOffsets=offsets;saveSettings();applySettings();scheduleNativePrayers();showToast('تم حفظ التعديلات وإعادة الجدولة');
  });
  $('requestNativePermissionsBtn').addEventListener('click',async()=>{ if ('Notification' in window) { const permission = await Notification.requestPermission().catch(()=> 'denied'); showToast(permission === 'granted' ? 'تم منح صلاحية الإشعارات' : 'لم يتم منح صلاحية الإشعارات'); refreshNativeStatus(); } else showToast('المتصفح لا يدعم الإشعارات'); });
  $('enableBackgroundUpdatesBtn').addEventListener('click',()=>{ if(hasNativeBridge()){nativeCall('enableBackgroundUpdates');state.settings.backgroundUpdates=true;saveSettings();setTimeout(refreshNativeStatus,500);} else showToast('التحديث الحقيقي بالخلفية متاح في تطبيق Android'); });
  $('openBatterySettingsBtn').addEventListener('click',()=>{ if(hasNativeBridge())nativeCall('openBatteryOptimizationSettings'); else showToast('افتح إعدادات البطارية من نظام التشغيل'); });
  $('openNotificationSettingsBtn').addEventListener('click',()=>{ if(hasNativeBridge())nativeCall('openNotificationSettings'); else showToast('افتح إعدادات الإشعارات من نظام التشغيل'); });
  $('clearCacheBtn').addEventListener('click',()=>{
    Object.values(CACHE_KEYS).forEach(k=>localStorage.removeItem(k));
    showToast('تم مسح بيانات الطقس والأسعار والصلاة المحفوظة');
  });

  document.addEventListener('visibilitychange',()=>{
    if (!document.hidden) {
      refreshNativeStatus();
      if (state.timer.running) tickTimer();
      if (state.stopwatch.running) tickStopwatch();
      refreshAll(true);
    }
  });
  window.addEventListener('beforeunload',()=>{saveTimerState();saveStopwatchState();});
  window.addEventListener('beforeinstallprompt', e => { e.preventDefault(); state.installPrompt=e; $('installBtn').classList.remove('hidden'); });
  $('installBtn').addEventListener('click',async()=>{if(!state.installPrompt)return;state.installPrompt.prompt();await state.installPrompt.userChoice;state.installPrompt=null;$('installBtn').classList.add('hidden');});
  window.addEventListener('appinstalled',()=>showToast(tr('تم تثبيت نبض القاهرة','Cairo Pulse installed')));
}


function resolveRouteTarget(hashValue = location.hash) {
  const raw = String(hashValue || '#home').replace(/^#/, '') || 'home';
  const target = document.getElementById(raw);
  const section = target?.closest?.('section.section-block');
  return { raw, sectionId: section?.id || (document.getElementById(raw)?.matches?.('section.section-block') ? raw : 'home'), target };
}
function applyAppRoute(options = {}) {
  const { raw, sectionId, target } = resolveRouteTarget();
  document.body.dataset.route = raw;
  document.querySelectorAll('main > section.page-view').forEach(section => {
    section.classList.toggle('active-page', section.id === sectionId);
    section.setAttribute('aria-hidden', section.id === sectionId ? 'false' : 'true');
  });
  document.querySelectorAll('.detail-dock .dock-link').forEach(link => {
    const linkRaw = String(link.getAttribute('href') || '').replace(/^#/, '');
    const linkSection = resolveRouteTarget(`#${linkRaw}`).sectionId;
    link.classList.toggle('active', linkRaw === raw || (raw === sectionId && linkSection === sectionId));
  });
  requestAnimationFrame(() => {
    if (sectionId === 'home' || !target || target.id === sectionId) {
      window.scrollTo({ top: 0, behavior: options.instant ? 'auto' : 'smooth' });
    } else {
      target.scrollIntoView({ block: 'start', behavior: options.instant ? 'auto' : 'smooth' });
    }
  });
}
function initRouting() {
  if (!location.hash) history.replaceState(null, '', '#home');
  window.addEventListener('hashchange', () => applyAppRoute());
  applyAppRoute({ instant: true });
}

async function registerServiceWorker() {
  if ('serviceWorker' in navigator && location.protocol.startsWith('http')) {
    try { await navigator.serviceWorker.register('./sw.js'); } catch (e) { console.warn('SW registration failed',e); }
  }
}

async function init() {
  loadSettings();
  populateCurrencySelects();
  applySettings();
  bindEvents();
  loadPedometer();
  loadTimerState();
  loadStopwatchState();
  loadCalcHistory();
  loadSpeedHistory();
  loadCachedData();
  hydrateNativeBackgroundData();
  updateConnectionStatus();
  initBattery();
  registerServiceWorker();
  updateClock();
  const webAudio = await getWebAdhanFile().catch(() => null);
  if (!hasNativeBridge() && webAudio?.name) $('adhanAudioStatus').textContent = `الصوت الحالي: ${webAudio.name}`;
  refreshNativeStatus();
  state.native.statusTimer = setInterval(refreshNativeStatus, 5000);
  setInterval(updateClock, 250);
  setInterval(updatePrayerCountdown, 1000);
  setInterval(updatePrayerTimeline, 30000);
  setInterval(() => {
    if (cairoDateKey() !== state.pedometer.date) {
      state.pedometer.date=cairoDateKey();
      if (!hasNativeBridge()) { state.pedometer.steps=0;saveSteps();renderSteps(); }
      refreshPrayer(true);
    }
  }, 60000);
  await refreshAll(true);
}


function syncNativeWidgetData(force = false) {
  if (!window.CairoNative || typeof window.CairoNative.syncWidgetData !== 'function') return;
  const now = cairoClockParts();
  const key = `${cairoDateKey()}-${now.hour}-${now.minute}`;
  if (!force && state.ui.lastWidgetSync === key) return;
  state.ui.lastWidgetSync = key;
  const next = nextPrayerInfo();
  const payload = {
    temperature: Number(state.weather?.current?.temperature_2m),
    weatherCode: Number(state.weather?.current?.weather_code),
    weatherDescription: $('weatherDesc')?.textContent || '',
    weatherIcon: $('weatherIcon')?.textContent || '☀️',
    nextPrayer: next?.ar || $('nextPrayerName')?.textContent || '—',
    nextPrayerKey: next?.key || '',
    nextPrayerTime: next?.time || $('nextPrayerTime')?.textContent || '--:--',
    remaining: $('prayerCountdown')?.textContent || '--:--:--',
    updatedAt: Date.now()
  };
  nativeCall('syncWidgetData', JSON.stringify(payload));
}

function syncHomeSummary() {
  const copy = (fromId, toId) => {
    const from = $(fromId), to = $(toId);
    if (from && to) to.textContent = from.textContent;
  };
  copy('currentTemp', 'homeTemp');
  copy('weatherDesc', 'homeWeatherDesc');
  copy('weatherIcon', 'homeWeatherIcon');
  copy('nextPrayerName', 'homeNextPrayerName');
  copy('nextPrayerTime', 'homeNextPrayerTime');
  copy('prayerCountdown', 'homePrayerCountdown');
  copy('gold21', 'homeGold21');
  copy('currentTemp', 'widgetPreviewTemp');
  copy('weatherDesc', 'widgetPreviewWeatherDesc');
  copy('weatherIcon', 'widgetPreviewWeatherIcon');
  copy('nextPrayerName', 'widgetPreviewPrayer');
  const countdown = $('prayerCountdown')?.textContent || '--:--:--';
  if ($('widgetPreviewRemaining')) $('widgetPreviewRemaining').textContent = countdown.slice(0,5);
  syncNativeWidgetData(false);
}
function initHomeSummarySync() {
  syncHomeSummary();
  ['currentTemp','weatherDesc','weatherIcon','nextPrayerName','nextPrayerTime','prayerCountdown','gold21'].forEach(id => {
    const el = $(id);
    if (el) new MutationObserver(syncHomeSummary).observe(el, {childList:true,subtree:true,characterData:true});
  });
}

document.addEventListener('DOMContentLoaded', async () => { initRouting(); await init(); initHomeSummarySync(); applyAppRoute({ instant: true }); });
