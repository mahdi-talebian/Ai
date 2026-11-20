import './styles.css';

const verse = 'بِسۡمِ ٱللَّهِ ٱلرَّحۡمَٰنِ ٱلرَّحِيمِ';
const arVerse = 'بِسۡمِ ٱللَّهِ ٱلرَّحۡمَٰنِ ٱلرَّحِيمِ';
const state = {
  lang: 'en',
  sound: true,
  recording: false,
  recordedUrl: '',
  stream: null,
  recorder: null,
  chunks: [],
  audioContext: null,
  analyser: null,
  animation: 0,
  currentLesson: 0,
  pitchFrames: [],
  analysis: null
};

const copy = {
  en: {
    reciter: 'Abdul Basit Abdul Samad',
    crumb: 'Al-Faatiha · verse 1 · Bismillah',
    feedback: 'Feedback',
    signIn: 'Sign in',
    more: 'More',
    instruction: 'Hear the reciter, then press Record and recite the basmala.',
    hear: 'Hear him',
    record: 'Record',
    stop: 'Stop',
    skip: 'Skip',
    audioQuestion: 'Would you like an audio journey, or would you rather go through it silently?',
    withSound: 'With sound',
    silently: 'Silently',
    rising: 'A voice goes up and comes down',
    risingBody: 'In one minute you will see how a reciter’s voice rises and falls, and then you will see your own.',
    pitch: 'Pitch',
    pitchBody: 'A higher voice and a lower voice',
    lower: 'A lower voice',
    higher: 'A higher voice',
    range: 'Every voice has a range',
    rangeBody: 'Let us find your range',
    begin: 'Let us begin',
    listenTitle: 'Listen to the basmala',
    listen: 'Listen',
    turn: 'Now it is your turn',
    scroll: 'Scroll down',
    playMine: 'Play my recording',
    playBoth: 'Play both',
    compare: 'Compare your voice with his',
    permission: 'Allow microphone access to record your recitation.',
    recorded: 'Recording ready. You can listen or record again.',
    recording: 'Listening…',
    noRecording: 'Record your voice first to hear it here.',
    analysisTitle: 'Your recording',
    voiced: 'Voiced',
    medianPitch: 'Middle pitch',
    rangeMetric: 'Range',
    steadiness: 'Steadiness',
    noVoice: 'No voiced frames were detected. Try moving closer to the microphone.',
    feedbackTitle: 'Tell us',
    feedbackText: 'What helped you most on this page?',
    send: 'Send',
    close: 'Close',
    moreTitle: 'More',
    reset: 'Reset practice',
    rangeDone: 'Your vocal range',
  },
  ar: {
    reciter: 'عبد الباسط عبد الصمد',
    crumb: 'الفاتحة · الآية ١ · البسملة',
    feedback: 'ملاحظات',
    signIn: 'تسجيل الدخول',
    more: 'المزيد',
    instruction: 'استمع إلى القارئ، ثم اضغط على «سجّل» وردّد البسملة.',
    hear: 'استمع إليه',
    record: 'سجّل',
    stop: 'إيقاف',
    skip: 'تخطَّ',
    audioQuestion: 'هل تودّ رحلةً صوتية، أم تفضّل أن تمرّ بها بصمت؟',
    withSound: 'مع الصوت',
    silently: 'بصمت',
    rising: 'الصوت يصعد ويهبط',
    risingBody: 'خلال دقيقة سترى كيف يرتفع صوت القارئ وينخفض، ثم سترى صوتك.',
    pitch: 'الطبقة الصوتية',
    pitchBody: 'صوت أعلى وصوت أخفض',
    lower: 'صوت أخفض',
    higher: 'صوت أعلى',
    range: 'لكل صوت مجاله',
    rangeBody: 'لنعرف مجالك الصوتي',
    begin: 'لنبدأ',
    listenTitle: 'استمع إلى البسملة',
    listen: 'استمع',
    turn: 'الآن دورك',
    scroll: 'مرّر إلى الأسفل',
    playMine: 'شغّل تسجيلي',
    playBoth: 'شغّل التسجيلين',
    compare: 'قارن صوتك بصوته',
    permission: 'اسمح بالوصول إلى الميكروفون لتسجيل تلاوتك.',
    recorded: 'التسجيل جاهز. يمكنك الاستماع أو التسجيل مرة أخرى.',
    recording: 'جارٍ الاستماع…',
    noRecording: 'سجّل صوتك أولاً لتسمعه هنا.',
    analysisTitle: 'تسجيلك',
    voiced: 'الصوت المنطوق',
    medianPitch: 'الطبقة الوسطى',
    rangeMetric: 'المجال',
    steadiness: 'الثبات',
    noVoice: 'لم يتم اكتشاف مقاطع صوتية منطوقة. حاول الاقتراب من الميكروفون.',
    feedbackTitle: 'أخبرنا برأيك',
    feedbackText: 'ما أكثر ما ساعدك في هذه الصفحة؟',
    send: 'إرسال',
    close: 'إغلاق',
    moreTitle: 'المزيد',
    reset: 'إعادة التدريب',
    rangeDone: 'مجالك الصوتي',
  }
};

const app = document.querySelector('#root');
app.innerHTML = `
  <main class="practice-shell">
    <header class="topbar">
      <button class="reciter-pill" data-action="reciter" aria-label="Select reciter">
        <span class="mini-mark">✺</span><span><strong data-copy="reciter"></strong><small data-copy="crumb"></small></span><b class="beta">BETA</b>
      </button>
      <div class="top-actions">
        <button class="text-button" data-action="feedback" data-copy="feedback"></button>
        <button class="text-button" data-action="signin" data-copy="signIn"></button>
        <button class="text-button" data-action="more" data-copy="more"></button>
        <button class="language-button" data-action="language"><span>English</span><i>العربية</i></button>
      </div>
    </header>

    <div class="side-dots" aria-hidden="true"><span class="active"></span><span></span><span></span><span></span><span></span></div>
    <button class="skip-link" data-action="skip" data-copy="skip"></button>

    <section class="lesson lesson-intro snap" id="intro">
      <div class="intro-copy">
        <div class="verse-display" dir="rtl"><span>بِسۡمِ ٱللَّهِ</span><span>ٱلرَّحۡمَٰنِ</span><span>ٱلرَّحِيمِ</span></div>
        <p class="instruction" data-copy="instruction"></p>
        <div class="button-row">
          <button class="round-action" data-action="hear"><span>▶</span><em data-copy="hear"></em></button>
          <button class="round-action record-action" data-action="record"><span>●</span><em data-copy="record"></em></button>
        </div>
      </div>
      <canvas class="hero-wave" data-chart="hero"></canvas>
      <div class="split-verse" dir="rtl"><button aria-label="Part 1">بِسۡمِ</button><button aria-label="Part 2">ٱللَّهِ</button><button aria-label="Part 3">ٱلرَّحۡمَٰنِ ٱلرَّحِيمِ</button></div>
      <button class="scroll-cue" data-action="next"><span data-copy="scroll"></span><b>↓</b></button>
    </section>

    <section class="lesson choice-section snap" id="choice">
      <div class="section-narrow">
        <h1 data-copy="audioQuestion"></h1>
        <div class="choice-row"><button class="choice-button selected" data-action="sound" data-copy="withSound"></button><button class="choice-button" data-action="silent" data-copy="silently"></button></div>
      </div>
    </section>

    <section class="lesson pitch-section snap" id="pitch">
      <div class="section-narrow split-heading"><div><h2 data-copy="rising"></h2><p data-copy="risingBody"></p></div><span class="chapter-index">01</span></div>
      <div class="pitch-stage"><canvas data-chart="pitch"></canvas><div class="pitch-labels"><span data-copy="pitch"></span><span data-copy="pitchBody"></span></div><div class="pitch-controls"><button data-action="lower" data-copy="lower"></button><button data-action="higher" data-copy="higher"></button></div></div>
    </section>

    <section class="lesson range-section snap" id="range">
      <div class="section-narrow"><span class="eyebrow">02</span><h2 data-copy="range"></h2><p data-copy="rangeBody"></p><button class="primary-button" data-action="begin" data-copy="begin"></button></div>
      <canvas class="range-wave" data-chart="range"></canvas>
    </section>

    <section class="lesson listen-section snap" id="listen">
      <div class="section-narrow"><span class="eyebrow">03</span><h2 data-copy="listenTitle"></h2><button class="audio-line" data-action="listen"><span class="play-icon">▶</span><span data-copy="listen"></span><span class="line"></span><span class="duration">0:12</span></button><div class="arabic-line" dir="rtl">${arVerse}</div><div class="dots-wave" data-chart="dots"></div></div>
    </section>

    <section class="lesson record-section snap" id="record">
      <div class="section-narrow"><span class="eyebrow">04</span><h2 data-copy="turn"></h2><p class="permission-note" data-copy="permission"></p><button class="primary-button record-big" data-action="record"><span>●</span><span data-copy="record"></span></button><div class="recording-status" role="status"></div><canvas class="record-canvas" data-chart="record"></canvas><div class="record-actions"><button class="ghost-button" data-action="playMine" data-copy="playMine"></button><button class="ghost-button" data-action="playBoth" data-copy="playBoth"></button></div><div class="analysis-panel" aria-live="polite"><div class="analysis-heading"><span class="eyebrow">ANALYSIS</span><span data-copy="analysisTitle"></span></div><div class="metric-grid"><div><strong data-metric="voiced">—</strong><small data-copy="voiced"></small></div><div><strong data-metric="median">—</strong><small data-copy="medianPitch"></small></div><div><strong data-metric="range">—</strong><small data-copy="rangeMetric"></small></div><div><strong data-metric="steady">—</strong><small data-copy="steadiness"></small></div></div></div></div>
    </section>

    <section class="lesson compare-section snap" id="compare">
      <div class="section-narrow compare-card"><span class="eyebrow">05</span><h2 data-copy="compare"></h2><div class="compare-rows"><div><span class="row-label">Abdul Basit</span><canvas data-chart="master"></canvas></div><div><span class="row-label">You</span><canvas data-chart="you"></canvas></div></div><button class="primary-button" data-action="playBoth" data-copy="playBoth"></button></div>
    </section>

    <footer class="practice-footer"><span>Maqamat</span><button data-action="language">English / العربية</button><span>© 2026</span></footer>
  </main>
  <dialog class="modal" id="modal"><div class="modal-content"><button class="modal-close" data-action="close">×</button><span class="eyebrow" id="modalEyebrow"></span><h2 id="modalTitle"></h2><p id="modalText"></p><div class="modal-actions"><button class="primary-button" data-action="close" data-copy="close"></button><button class="ghost-button" data-action="send" data-copy="send"></button></div></div></dialog>
`;

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const t = (key) => copy[state.lang][key];

function applyLanguage() {
  document.documentElement.lang = state.lang;
  document.documentElement.dir = state.lang === 'ar' ? 'rtl' : 'ltr';
  $$('[data-copy]').forEach((node) => { node.textContent = t(node.dataset.copy); });
  $('.language-button span').textContent = state.lang === 'en' ? 'English' : 'العربية';
  $('.language-button i').textContent = state.lang === 'en' ? 'العربية' : 'English';
  $('.permission-note').hidden = state.lang !== 'en' && false;
}

function scrollToId(id) { document.getElementById(id)?.scrollIntoView({ behavior: 'smooth' }); }

function showModal(kind) {
  const modal = $('#modal');
  $('#modalEyebrow').textContent = kind === 'feedback' ? 'FEEDBACK' : 'MORE';
  $('#modalTitle').textContent = kind === 'feedback' ? t('feedbackTitle') : t('moreTitle');
  $('#modalText').textContent = kind === 'feedback' ? t('feedbackText') : `${t('rangeDone')} · ${t('reset')}`;
  modal.showModal();
}

function tone(seconds = 1.1, start = 330) {
  if (!state.sound) return;
  const Ctx = window.AudioContext || window.webkitAudioContext;
  if (!Ctx) return;
  const ctx = new Ctx();
  const osc = ctx.createOscillator(); const gain = ctx.createGain();
  osc.type = 'sine'; osc.frequency.setValueAtTime(start, ctx.currentTime); osc.frequency.exponentialRampToValueAtTime(start * 1.5, ctx.currentTime + seconds * .75);
  gain.gain.setValueAtTime(.0001, ctx.currentTime); gain.gain.exponentialRampToValueAtTime(.07, ctx.currentTime + .04); gain.gain.exponentialRampToValueAtTime(.0001, ctx.currentTime + seconds);
  osc.connect(gain).connect(ctx.destination); osc.start(); osc.stop(ctx.currentTime + seconds + .05);
}

function drawWave(canvas, variant = 0, liveData = null) {
  if (!canvas) return;
  const ratio = Math.max(1, window.devicePixelRatio || 1); const rect = canvas.getBoundingClientRect();
  const w = Math.max(20, rect.width); const h = Math.max(20, rect.height); canvas.width = w * ratio; canvas.height = h * ratio;
  const ctx = canvas.getContext('2d'); ctx.scale(ratio, ratio); ctx.clearRect(0, 0, w, h);
  ctx.strokeStyle = 'rgba(221, 203, 132, .82)'; ctx.lineWidth = 1.4; ctx.beginPath();
  const data = liveData || [];
  for (let x = 0; x < w; x += 3) {
    let y;
    if (data.length) { const i = Math.floor((x / w) * data.length); y = h / 2 + (data[i] || 0) * h * .42; }
    else { const n = Math.sin(x * .024 + variant) * .15 + Math.sin(x * .065 + variant * 2) * .08 + Math.sin(x * .16 + variant) * .035; y = h / 2 + n * h; }
    if (x === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
  }
  ctx.stroke();
}

function drawDots() {
  $$('.dots-wave').forEach((node) => { node.innerHTML = ''; for (let i = 0; i < 44; i++) { const dot = document.createElement('i'); dot.style.setProperty('--delay', `${i * 22}ms`); dot.style.height = `${8 + Math.round(Math.abs(Math.sin(i * .75)) * 22)}px`; node.append(dot); } });
}

function renderCharts() {
  drawWave($('[data-chart="hero"]'), 1); drawWave($('[data-chart="pitch"]'), 2); drawWave($('[data-chart="range"]'), 4); drawWave($('[data-chart="master"]'), 2); drawWave($('[data-chart="you"]'), 7);
  drawDots();
}

function updateRecordStatus(message = '') { $('.recording-status').textContent = message; }

function detectPitch(buffer, sampleRate) {
  let rms = 0;
  for (const value of buffer) rms += value * value;
  rms = Math.sqrt(rms / buffer.length);
  if (rms < 0.018) return null;
  const minLag = Math.floor(sampleRate / 1000);
  const maxLag = Math.min(Math.floor(sampleRate / 65), buffer.length - 1);
  let bestLag = -1; let bestCorrelation = 0;
  for (let lag = minLag; lag <= maxLag; lag += 1) {
    let correlation = 0;
    for (let i = 0; i < buffer.length - lag; i += 1) correlation += buffer[i] * buffer[i + lag];
    correlation /= buffer.length - lag;
    if (correlation > bestCorrelation) { bestCorrelation = correlation; bestLag = lag; }
  }
  if (bestLag < 0 || bestCorrelation < 0.01) return null;
  return sampleRate / bestLag;
}

function median(values) {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted.length ? sorted[Math.floor(sorted.length / 2)] : 0;
}

function analysePitchFrames() {
  const voiced = state.pitchFrames.filter((frame) => frame.hz);
  if (!voiced.length) { state.analysis = null; updateRecordStatus(t('noVoice')); updateMetrics(); return; }
  const pitches = voiced.map((frame) => frame.hz); const middle = median(pitches);
  const average = pitches.reduce((sum, hz) => sum + hz, 0) / pitches.length;
  const deviation = Math.sqrt(pitches.reduce((sum, hz) => sum + ((hz - average) ** 2), 0) / pitches.length);
  state.analysis = { voiced: voiced.length / Math.max(1, state.pitchFrames.length), middle, low: Math.min(...pitches), high: Math.max(...pitches), steady: Math.max(0, Math.min(100, 100 - (deviation / middle) * 100)) };
  updateMetrics();
  drawPitchTrack($('[data-chart="you"]'), pitches);
}

function updateMetrics() {
  const values = state.analysis;
  $('[data-metric="voiced"]').textContent = values ? `${Math.round(values.voiced * 100)}%` : '—';
  $('[data-metric="median"]').textContent = values ? `${Math.round(values.middle)} Hz` : '—';
  $('[data-metric="range"]').textContent = values ? `${Math.round(values.low)}–${Math.round(values.high)} Hz` : '—';
  $('[data-metric="steady"]').textContent = values ? `${Math.round(values.steady)}%` : '—';
}

function drawPitchTrack(canvas, pitches) {
  if (!canvas || !pitches?.length) return;
  const ratio = Math.max(1, window.devicePixelRatio || 1); const rect = canvas.getBoundingClientRect(); const w = Math.max(20, rect.width); const h = Math.max(20, rect.height);
  canvas.width = w * ratio; canvas.height = h * ratio; const ctx = canvas.getContext('2d'); ctx.scale(ratio, ratio); ctx.clearRect(0, 0, w, h);
  const low = Math.max(50, Math.min(...pitches) - 20); const high = Math.max(...pitches) + 20;
  ctx.strokeStyle = 'rgba(221, 203, 132, .9)'; ctx.lineWidth = 1.6; ctx.beginPath();
  pitches.forEach((pitch, index) => { const x = (index / Math.max(1, pitches.length - 1)) * w; const y = h - ((pitch - low) / Math.max(1, high - low)) * h; index ? ctx.lineTo(x, y) : ctx.moveTo(x, y); }); ctx.stroke();
}

async function toggleRecording() {
  if (state.recording) { state.recorder?.stop(); return; }
  if (!navigator.mediaDevices?.getUserMedia) { updateRecordStatus('Microphone recording is not supported in this browser.'); return; }
  try {
    state.stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    state.audioContext = new (window.AudioContext || window.webkitAudioContext)();
    const source = state.audioContext.createMediaStreamSource(state.stream); state.analyser = state.audioContext.createAnalyser(); state.analyser.fftSize = 256; source.connect(state.analyser);
    state.pitchFrames = []; state.analysis = null; updateMetrics();
    state.recorder = new MediaRecorder(state.stream); state.chunks = [];
    state.recorder.ondataavailable = (event) => event.data.size && state.chunks.push(event.data);
    state.recorder.onstop = () => {
      const blob = new Blob(state.chunks, { type: state.recorder.mimeType || 'audio/webm' });
      if (state.recordedUrl) URL.revokeObjectURL(state.recordedUrl); state.recordedUrl = URL.createObjectURL(blob);
      state.stream?.getTracks().forEach((track) => track.stop()); state.recording = false; analysePitchFrames(); if (state.analysis) updateRecordStatus(t('recorded')); updateRecordingButtons(); drawWave($('[data-chart="record"]'), 8);
    };
    state.recorder.start(); state.recording = true; updateRecordStatus(t('recording')); updateRecordingButtons(); animateRecording();
  } catch (error) { updateRecordStatus(`${t('permission')} (${error.name})`); }
}

function animateRecording() {
  if (!state.recording || !state.analyser) return;
  const values = new Float32Array(state.analyser.fftSize); state.analyser.getFloatTimeDomainData(values);
  const pitch = detectPitch(values, state.audioContext.sampleRate); state.pitchFrames.push({ hz: pitch });
  drawWave($('[data-chart="record"]'), 8, [...values]); state.animation = requestAnimationFrame(animateRecording);
}

function updateRecordingButtons() { $$('[data-action="record"]').forEach((button) => { const text = button.querySelector('em,[data-copy="record"]'); if (text) text.textContent = state.recording ? t('stop') : t('record'); }); }

function playMine() {
  if (!state.recordedUrl) { updateRecordStatus(t('noRecording')); return; }
  new Audio(state.recordedUrl).play();
}

function handleAction(action) {
  if (action === 'language') { state.lang = state.lang === 'en' ? 'ar' : 'en'; applyLanguage(); return; }
  if (action === 'skip') { scrollToId('choice'); return; }
  if (action === 'next') { scrollToId('choice'); return; }
  if (action === 'feedback' || action === 'more') { showModal(action); return; }
  if (action === 'close') { $('#modal').close(); return; }
  if (action === 'send') { $('#modalText').textContent = 'Thank you.'; return; }
  if (action === 'hear' || action === 'listen') { tone(action === 'listen' ? 1.7 : 1.2, action === 'listen' ? 280 : 390); return; }
  if (action === 'record') { toggleRecording(); return; }
  if (action === 'sound') { state.sound = true; $$('.choice-button').forEach((b) => b.classList.remove('selected')); $('[data-action="sound"]').classList.add('selected'); tone(); return; }
  if (action === 'silent') { state.sound = false; $$('.choice-button').forEach((b) => b.classList.remove('selected')); $('[data-action="silent"]').classList.add('selected'); return; }
  if (action === 'begin') { scrollToId('listen'); return; }
  if (action === 'playMine') { playMine(); return; }
  if (action === 'playBoth') { tone(1.2, 340); setTimeout(playMine, 1300); return; }
  if (action === 'reciter') { showModal('more'); return; }
}

document.addEventListener('click', (event) => { const target = event.target.closest('[data-action]'); if (target) handleAction(target.dataset.action); });
window.addEventListener('resize', renderCharts);
const observer = new IntersectionObserver((entries) => entries.forEach((entry) => { if (entry.isIntersecting) { $$('.side-dots span').forEach((dot, index) => dot.classList.toggle('active', index === Math.min(4, Math.round(entry.target.offsetTop / window.innerHeight)))); } }), { threshold: .55 });
$$('.lesson').forEach((section) => observer.observe(section));
applyLanguage(); renderCharts(); updateRecordStatus('');
