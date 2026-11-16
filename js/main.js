import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { EffectComposer } from 'three/addons/postprocessing/EffectComposer.js';
import { RenderPass } from 'three/addons/postprocessing/RenderPass.js';
import { UnrealBloomPass } from 'three/addons/postprocessing/UnrealBloomPass.js';
import { OutputPass } from 'three/addons/postprocessing/OutputPass.js';
import { loadBoss, loadArmorStand, loadItem, noiseTexture } from './mc3d.js';
import { BOSSES, ITEMS, BIOMES, STRUCTURES, SPLASHES } from './data.js';

const A = 'assets';
const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const reduceMotion = matchMedia('(prefers-reduced-motion: reduce)').matches;

// ============================================================ Sprache

let lang = document.documentElement.dataset.lang || 'de';
const t = (o) => (o && typeof o === 'object' ? (o[lang] ?? o.de) : o);
const langListeners = [];

function setLang(l) {
  lang = l;
  document.documentElement.dataset.lang = l;
  document.documentElement.lang = l;
  try { localStorage.setItem('hha-lang', l); } catch (e) { /* privat */ }
  $$('[data-set-lang]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.setLang === l)));
  langListeners.forEach((f) => f());
}
$$('[data-set-lang]').forEach((b) => b.addEventListener('click', () => setLang(b.dataset.setLang)));

// ============================================================ Splash-Text

function splash() {
  const list = SPLASHES[lang];
  $('#splash').textContent = list[Math.floor(Math.random() * list.length)];
}
langListeners.push(splash);
$('#splash').addEventListener('click', splash);

// ============================================================ Pixel-Hintergründe (eigene, prozedural)

function pixelURL(colors, size = 16, seed = 7) {
  const c = document.createElement('canvas');
  c.width = size; c.height = size;
  const g = c.getContext('2d');
  let s = seed;
  const rnd = () => ((s = (s * 16807) % 2147483647) / 2147483647);
  for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
    g.fillStyle = colors[Math.floor(rnd() * colors.length)];
    g.fillRect(x, y, 1, 1);
  }
  return `url(${c.toDataURL()})`;
}
const root = document.documentElement.style;
root.setProperty('--tex-stone', pixelURL(['#1d181d', '#211b21', '#191419', '#241e24', '#1b161b'], 16, 3));
root.setProperty('--tex-dirt', pixelURL(['#2a1e16', '#33251b', '#241a13', '#3b2b1f', '#2e2118'], 16, 11));
root.setProperty('--tex-black', pixelURL(['#151015', '#19131a', '#120e13', '#1d161d'], 16, 5));
root.setProperty('--tex-brick', pixelURL(['#1e1015', '#24131a', '#1a0d12', '#2a161d'], 16, 9));
root.setProperty('--tex-btn', pixelURL(['#6f6f6f', '#747474', '#6a6a6a', '#787878', '#717171'], 16, 13));
root.setProperty('--tex-panel', pixelURL(['#c6c6c6', '#c3c3c3', '#c9c9c9', '#c6c6c6'], 16, 17));

// ============================================================ Tooltip (Minecraft-Stil)

const tip = $('#mc-tooltip');
let tipOwner = null;
function showTip(html, x, y, owner) {
  tip.innerHTML = html;
  tip.hidden = false;
  tipOwner = owner;
  moveTip(x, y);
}
function moveTip(x, y) {
  const r = tip.getBoundingClientRect();
  let left = x + 16, top = y - 12;
  if (left + r.width > innerWidth - 8) left = x - r.width - 16;
  if (left < 8) left = 8;
  if (top + r.height > innerHeight - 8) top = innerHeight - r.height - 8;
  if (top < 8) top = 8;
  tip.style.left = left + 'px';
  tip.style.top = top + 'px';
}
function hideTip() { tip.hidden = true; tipOwner = null; }
document.addEventListener('scroll', hideTip, { passive: true });
document.addEventListener('pointerdown', (e) => { if (tipOwner && !tipOwner.contains(e.target)) hideTip(); });

// ============================================================ Inventar

function itemTooltip(item, side) {
  const cls = side === 'heaven' ? 'mc-gold' : 'mc-aqua';
  const lines = item.lore.map((l) => `<div class="${l.set ? 'mc-blue' : 'mc-gray'}">${t(l)}</div>`).join('');
  return `<div class="${cls} tt-name">${t(item.name)}</div>${lines}`;
}

function buildInventories() {
  for (const inv of $$('.inventory')) {
    const side = inv.dataset.side;
    inv.innerHTML = '';
    for (const item of ITEMS[side]) {
      const slot = document.createElement('button');
      slot.className = 'slot';
      slot.type = 'button';
      slot.setAttribute('aria-label', t(item.name));
      const icon = document.createElement('span');
      icon.className = `icon frames${item.frames}`;
      icon.style.backgroundImage = `url(${A}/item/${item.tex}.png)`;
      slot.appendChild(icon);
      const show = (e) => showTip(itemTooltip(item, side), e.clientX, e.clientY, slot);
      slot.addEventListener('pointerenter', (e) => { if (e.pointerType === 'mouse') show(e); });
      slot.addEventListener('pointermove', (e) => { if (tipOwner === slot) moveTip(e.clientX, e.clientY); });
      slot.addEventListener('pointerleave', (e) => { if (e.pointerType === 'mouse') hideTip(); });
      slot.addEventListener('click', (e) => {
        const r = slot.getBoundingClientRect();
        if (tipOwner === slot) hideTip(); else showTip(itemTooltip(item, side), r.right, r.top + 10, slot);
        e.stopPropagation();
      });
      slot.addEventListener('focus', () => { const r = slot.getBoundingClientRect(); showTip(itemTooltip(item, side), r.right, r.top + 10, slot); });
      slot.addEventListener('blur', hideTip);
      inv.appendChild(slot);
    }
  }
}
langListeners.push(buildInventories);

// ============================================================ Biome und Galerie

function buildBiomes() {
  const box = $('#biomes');
  box.innerHTML = '';
  BIOMES.forEach((b, i) => {
    const card = document.createElement('article');
    card.className = 'biome';
    card.innerHTML = `<div class="biome-tex" style="background-image:${pixelURL(b.colors, 16, 31 + i * 7)}"></div>
      <div class="biome-body"><h4>${t(b.name)}</h4><p>${t(b.desc)}</p></div>`;
    box.appendChild(card);
  });
}
langListeners.push(buildBiomes);

const lightbox = $('#lightbox');
function openLightbox(s) {
  $('img', lightbox).src = `${A}/img/${s.img}.webp`;
  $('img', lightbox).alt = t(s.name);
  $('.lb-caption', lightbox).innerHTML = `<b>${t(s.name)}</b> · ${t(s.desc)}`;
  lightbox.hidden = false;
  $('.lb-close', lightbox).focus();
}
lightbox.addEventListener('click', (e) => { if (e.target === lightbox || e.target.closest('.lb-close')) lightbox.hidden = true; });
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') { lightbox.hidden = true; hideTip(); } });

function buildGallery() {
  const box = $('#gallery');
  box.innerHTML = '';
  STRUCTURES.forEach((s, i) => {
    const fig = document.createElement('figure');
    fig.className = 'shot' + (i === 0 ? ' wide' : '');
    fig.innerHTML = `<button type="button" aria-label="${t(s.name)}"><img src="${A}/img/${s.img}.webp" alt="${t(s.name)}" loading="lazy"></button>
      <figcaption><b>${t(s.name)}</b><span>${t(s.desc)}</span></figcaption>`;
    $('button', fig).addEventListener('click', () => openLightbox(s));
    box.appendChild(fig);
  });
}
langListeners.push(buildGallery);

// ============================================================ Render-Schleife (nur sichtbare Szenen)

const views = [];
function makeView(canvas, { bloom = false, alpha = true, shadows = false } = {}) {
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha, powerPreference: 'high-performance' });
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  if (shadows) { renderer.shadowMap.enabled = true; renderer.shadowMap.type = THREE.PCFSoftShadowMap; }
  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(35, 1, 0.05, 200);
  const view = { canvas, renderer, scene, camera, visible: false, frame: null, composer: null, w: 0, h: 0 };
  if (bloom) {
    const composer = new EffectComposer(renderer);
    composer.addPass(new RenderPass(scene, camera));
    view.bloomPass = new UnrealBloomPass(new THREE.Vector2(256, 256), 0.55, 0.45, 0.8);
    composer.addPass(view.bloomPass);
    composer.addPass(new OutputPass());
    view.composer = composer;
  }
  new IntersectionObserver((es) => { view.visible = es[0].isIntersecting; }, { rootMargin: '120px' }).observe(canvas);
  views.push(view);
  return view;
}

function fit(view) {
  const w = view.canvas.clientWidth, h = view.canvas.clientHeight;
  if (w === view.w && h === view.h) return;
  view.w = w; view.h = h;
  view.renderer.setSize(w, h, false);
  if (view.composer) view.composer.setSize(w, h);
  view.camera.aspect = w / Math.max(1, h);
  view.camera.updateProjectionMatrix();
}

let last = performance.now();
function loop(now) {
  const dt = Math.min(0.05, (now - last) / 1000);
  last = now;
  for (const v of views) {
    if (!v.visible || !v.frame) continue;
    fit(v);
    v.frame(now, dt);
    if (v.composer) v.composer.render(); else v.renderer.render(v.scene, v.camera);
  }
  requestAnimationFrame(loop);
}
requestAnimationFrame(loop);

// ============================================================ Gemeinsame Bausteine

function makeParticles(count, color, area, size) {
  const g = new THREE.BufferGeometry();
  const pos = new Float32Array(count * 3);
  const speed = new Float32Array(count);
  for (let i = 0; i < count; i++) {
    pos[i * 3] = area.x[0] + Math.random() * (area.x[1] - area.x[0]);
    pos[i * 3 + 1] = area.y[0] + Math.random() * (area.y[1] - area.y[0]);
    pos[i * 3 + 2] = area.z[0] + Math.random() * (area.z[1] - area.z[0]);
    speed[i] = 0.15 + Math.random() * 0.35;
  }
  g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  const m = new THREE.PointsMaterial({ color, size, transparent: true, opacity: 0.9, blending: THREE.AdditiveBlending, depthWrite: false });
  const pts = new THREE.Points(g, m);
  pts.userData.tick = (dt, now) => {
    for (let i = 0; i < count; i++) {
      pos[i * 3 + 1] += speed[i] * dt;
      pos[i * 3] += Math.sin(now * 0.001 + i) * 0.05 * dt;
      if (pos[i * 3 + 1] > area.y[1]) pos[i * 3 + 1] = area.y[0];
    }
    g.attributes.position.needsUpdate = true;
  };
  return pts;
}

function blockBox(tex) {
  return new THREE.Mesh(new THREE.BoxGeometry(1, 1, 1), new THREE.MeshLambertMaterial({ map: tex }));
}

// ============================================================ Hero: Heaven gegen Hell

async function initHero() {
  const view = makeView($('#hero-canvas'), { alpha: true, shadows: true });
  const { scene, camera } = view;
  camera.fov = 30;
  scene.add(new THREE.HemisphereLight(0xfff4e0, 0x241828, 1.1));
  const sun = new THREE.DirectionalLight(0xfff0d0, 1.6);
  sun.position.set(-3, 6, 5);
  sun.castShadow = true;
  sun.shadow.mapSize.set(1024, 1024);
  Object.assign(sun.shadow.camera, { left: -5, right: 5, top: 5, bottom: -3 });
  scene.add(sun);
  const goldLight = new THREE.PointLight(0xffc75a, 6, 6, 1.6); goldLight.position.set(-2.4, 2.2, 1.5); scene.add(goldLight);
  const soulLight = new THREE.PointLight(0x39d5ff, 8, 6, 1.6); soulLight.position.set(2.4, 2.0, 1.5); scene.add(soulLight);

  // Schwebende Insel: links Marmor mit Gold, rechts dunkles Netherite mit Seelenglut
  const marble = noiseTexture(['#ece8df', '#e2ddd2', '#f4f1ea', '#d8d2c6', '#e9e3d6'], 16, 21);
  const marbleTop = noiseTexture(['#ece8df', '#f4f1ea', '#e2ddd2', '#f1c94e', '#ece8df'], 16, 22, '#e5b93d');
  const dark = noiseTexture(['#2b2630', '#25212a', '#312b36', '#1f1b23', '#2b2630'], 16, 23);
  const darkTop = noiseTexture(['#2b2630', '#25212a', '#312b36', '#1f1b23', '#2fbfe8'], 16, 24, '#1f8fbf');
  const island = new THREE.Group();
  const layers = [[8, 4, 0], [6, 3, -1], [4, 2, -2], [2, 1, -3]];
  for (const [w, d, y] of layers) {
    for (let x = 0; x < w; x++) for (let z = 0; z < d; z++) {
      if (y < 0 && Math.random() < 0.18) continue;
      const left = x < w / 2;
      const top = y === 0;
      const b = blockBox(left ? (top ? marbleTop : marble) : (top ? darkTop : dark));
      b.position.set(x - w / 2 + 0.5, y - 0.5, z - d / 2 + 0.5);
      b.receiveShadow = true;
      b.castShadow = y === 0;
      island.add(b);
    }
  }
  scene.add(island);

  const [heaven, hell, hSword, lSword] = await Promise.all([
    loadArmorStand(A, 'heaven', '#ffd75e'), loadArmorStand(A, 'hell', '#39d5ff'),
    loadItem(`${A}/item/heavens_sword.png`), loadItem(`${A}/item/hells_sword.png`),
  ]);
  const placeHand = (stand, item) => {
    const hold = new THREE.Group();
    hold.position.set(-1, 10, -1);
    // Gegen die Spiegelung des Modellraums, dann wie ein gehaltenes Schwert neigen
    hold.scale.set(-16, -16, 16);
    const tilt = new THREE.Group();
    tilt.rotation.set(0, Math.PI / 2, 0);
    item.mesh.position.set(0.28, 0.28, 0);
    item.mesh.rotation.z = 0;
    const inner = new THREE.Group();
    inner.rotation.x = -0.9;
    inner.add(tilt);
    tilt.add(item.mesh);
    hold.add(inner);
    stand.parts.arm_r.add(hold);
  };
  placeHand(heaven, hSword);
  placeHand(hell, lSword);
  const hHolder = new THREE.Group(); hHolder.add(heaven.root); hHolder.position.set(-1.2, 0, 0.3); hHolder.rotation.y = 0.5;
  const lHolder = new THREE.Group(); lHolder.add(hell.root); lHolder.position.set(1.2, 0, 0.3); lHolder.rotation.y = -0.5;
  scene.add(hHolder, lHolder);

  const motes = makeParticles(70, 0xffd36a, { x: [-5, -0.3], y: [-1, 4.5], z: [-2, 2] }, 0.06);
  const embers = makeParticles(70, 0x3fd8ff, { x: [0.3, 5], y: [-1, 4.5], z: [-2, 2] }, 0.06);
  scene.add(motes, embers);

  let mx = 0, my = 0;
  addEventListener('pointermove', (e) => { mx = e.clientX / innerWidth - 0.5; my = e.clientY / innerHeight - 0.5; }, { passive: true });
  const target = new THREE.Vector3(0, 0.7, 0);
  view.frame = (now, dt) => {
    const s = reduceMotion ? 0 : now / 1000;
    const yaw = Math.sin(s * 0.18) * 0.22 + mx * 0.35;
    const dist = view.w < 560 ? 8.8 : 8.4;
    camera.position.set(Math.sin(yaw) * dist, 2.3 - my * 0.6, Math.cos(yaw) * dist);
    camera.lookAt(target);
    island.position.y = Math.sin(s * 0.8) * 0.04;
    for (const [st, ph] of [[heaven, 0], [hell, Math.PI]]) {
      const p = st.parts;
      p.arm_r.rotation.set(-0.55 + Math.sin(s * 1.3 + ph) * 0.05, 0.05, 0.08, 'ZYX');
      p.arm_l.rotation.set(Math.sin(s * 1.1 + ph) * 0.06, 0, -0.08 - Math.sin(s * 1.1 + ph) * 0.04, 'ZYX');
      p.head.rotation.set(Math.sin(s * 0.7 + ph) * 0.06, Math.sin(s * 0.45 + ph) * 0.25, 0, 'ZYX');
      p.leg_r.rotation.x = 0.06; p.leg_l.rotation.x = -0.06;
    }
    hHolder.position.y = island.position.y; lHolder.position.y = island.position.y;
    hSword.tick(now); lSword.tick(now);
    motes.userData.tick(dt, now); embers.userData.tick(dt, now);
    goldLight.intensity = 6 + Math.sin(s * 2.1) * 1.2;
    soulLight.intensity = 8 + Math.sin(s * 2.7 + 1) * 1.6;
  };
}

// ============================================================ Sets: schwebende Waffen

async function initItemShowcase(canvas) {
  const side = canvas.dataset.side;
  const view = makeView(canvas, { alpha: true });
  const { scene, camera } = view;
  camera.fov = 30;
  camera.position.set(0, 0.15, 3.3);
  camera.lookAt(0, 0, 0);
  scene.add(new THREE.HemisphereLight(0xffffff, 0x302030, 1.3));
  const key = new THREE.DirectionalLight(side === 'heaven' ? 0xfff0c8 : 0xbfefff, 1.6);
  key.position.set(1, 2, 3);
  scene.add(key);
  const names = side === 'heaven' ? ['heavens_sword', 'heaven_ingot', 'heavens_mace'] : ['hells_sword', 'hell_ingot', 'hells_mace'];
  const items = await Promise.all(names.map((n) => loadItem(`${A}/item/${n}.png`)));
  const groups = items.map((it, i) => {
    const g = new THREE.Group();
    g.add(it.mesh);
    g.position.x = (i - 1) * 1.05;
    g.scale.setScalar(i === 1 ? 0.62 : 0.9);
    scene.add(g);
    return g;
  });
  const parts = makeParticles(40, side === 'heaven' ? 0xffd36a : 0x3fd8ff, { x: [-1.8, 1.8], y: [-0.9, 0.9], z: [-0.6, 0.4] }, 0.035);
  scene.add(parts);
  view.frame = (now, dt) => {
    const s = reduceMotion ? 0 : now / 1000;
    groups.forEach((g, i) => {
      g.rotation.y = s * 1.1 + i * 1.3;
      g.position.y = Math.sin(s * 2 + i * 1.7) * 0.07;
    });
    items.forEach((it) => it.tick(now));
    parts.userData.tick(dt, now);
  };
}

// ============================================================ Bestiarium

function timing(p) {
  if (/windup|raise|lift|charge|inhale|reach|_set|cast|coil|emerge|flip|scoop|ascend|vanish|step/.test(p)) return { ms: 560, hold: 260 };
  if (/hit|throw|release|dash$|dive$|bite|spit|fire|pull|slash|thrust$|push|roll$|spokes$|grab|devour_bite|_3$/.test(p)) return { ms: 150, hold: 520, ease: 'snap' };
  return { ms: 430, hold: 420 };
}

const bossCache = new Map();
function bossModel(b) {
  const key = b.model + '|' + (b.texture || '');
  if (!bossCache.has(key)) bossCache.set(key, loadBoss(A, b.model, b.texture));
  return bossCache.get(key);
}

function bossBar(b) {
  const bar = $('#bb-bar');
  bar.innerHTML = '';
  bar.style.setProperty('--bb', b.color);
  const n = b.segments || 1;
  for (let i = 0; i < n; i++) {
    const seg = document.createElement('span');
    seg.className = 'bb-seg';
    if (n === 1) seg.classList.add('notched');
    bar.appendChild(seg);
  }
  $('#bb-name').textContent = t(b.name);
}

async function initBestiary() {
  const canvas = $('#boss-canvas');
  const view = makeView(canvas, { bloom: true, alpha: false, shadows: true });
  const { scene, camera, renderer } = view;
  scene.background = new THREE.Color(0x150d12);
  scene.fog = new THREE.Fog(0x150d12, 14, 34);
  camera.fov = 32;
  scene.add(new THREE.HemisphereLight(0xffeedd, 0x2a1820, 1.25));
  const key = new THREE.DirectionalLight(0xfff2e0, 1.9);
  key.position.set(-4, 9, 7);
  key.castShadow = true;
  key.shadow.mapSize.set(2048, 2048);
  Object.assign(key.shadow.camera, { left: -8, right: 8, top: 10, bottom: -4, near: 1, far: 30 });
  key.shadow.bias = -0.0005;
  scene.add(key);
  const rim = new THREE.DirectionalLight(0x8f6cff, 1.4);
  rim.position.set(3, 4, -8);
  scene.add(rim);

  const floorTex = noiseTexture(['#2a2228', '#30272e', '#241d22', '#2d252b', '#272026'], 16, 41, '#1d171c');
  floorTex.wrapS = floorTex.wrapT = THREE.RepeatWrapping;
  floorTex.repeat.set(40, 40);
  const floor = new THREE.Mesh(new THREE.PlaneGeometry(40, 40), new THREE.MeshLambertMaterial({ map: floorTex }));
  floor.rotation.x = -Math.PI / 2;
  floor.receiveShadow = true;
  scene.add(floor);
  const rune = new THREE.Mesh(new THREE.RingGeometry(1.6, 1.75, 64), new THREE.MeshBasicMaterial({ color: 0xffc94a, transparent: true, opacity: 0.55, side: THREE.DoubleSide }));
  rune.rotation.x = -Math.PI / 2;
  rune.position.y = 0.01;
  scene.add(rune);
  const motes = makeParticles(60, 0xffb04a, { x: [-7, 7], y: [0, 9], z: [-7, 4] }, 0.05);
  scene.add(motes);

  const controls = new OrbitControls(camera, canvas);
  controls.enableDamping = true;
  controls.dampingFactor = 0.08;
  controls.autoRotate = !reduceMotion;
  controls.autoRotateSpeed = 1.1;
  controls.maxPolarAngle = Math.PI * 0.49;
  controls.enablePan = false;

  const holder = new THREE.Group();
  scene.add(holder);
  let current = null, entry = null, lastUser = performance.now(), frameTo = null;

  const frameModel = (m, instant) => {
    const h = m.size.y, w = Math.max(m.size.x, m.size.z);
    const tgt = new THREE.Vector3(0, h * 0.48, 0);
    const span = Math.max(h, w * 0.85);
    const dist = (span * 0.68) / Math.tan(THREE.MathUtils.degToRad(camera.fov / 2)) + w * 0.3;
    controls.minDistance = dist * 0.35;
    controls.maxDistance = dist * 2.2;
    rune.scale.setScalar(Math.max(0.6, w * 0.45));
    const dir = new THREE.Vector3(0.45, 0.22, 1).normalize();
    const pos = tgt.clone().addScaledVector(dir, dist);
    if (instant) { controls.target.copy(tgt); camera.position.copy(pos); }
    else frameTo = { t0: performance.now(), fromT: controls.target.clone(), fromP: camera.position.clone(), tgt, pos };
  };

  const info = $('#boss-info');
  function renderInfo() {
    if (!entry) return;
    const b = entry;
    info.style.setProperty('--accent', b.color);
    info.innerHTML = `
      <h3 class="tt-name" style="color:${b.color}">${t(b.name)}</h3>
      <p class="mc-gray sub">${t(b.sub)}</p>
      <p class="desc">${t(b.desc)}</p>
      <h4 class="mc-yellow">${lang === 'de' ? 'Der Trick' : 'The trick'}</h4>
      <p class="desc">${t(b.trick)}</p>
      <dl class="facts">
        <dt class="mc-gray">${lang === 'de' ? 'Fundort' : 'Found at'}</dt><dd>${t(b.where)}</dd>
        <dt class="mc-gray">${lang === 'de' ? 'Beute' : 'Loot'}</dt><dd>${t(b.loot)}</dd>
      </dl>
      <h4 class="mc-yellow">${lang === 'de' ? 'Angriffe abspielen' : 'Play attacks'}</h4>
      <div class="moves">${b.moves.map((mv, i) => `<button class="mc-btn small" data-move="${i}">${t(mv.n)}</button>`).join('')}</div>`;
    $$('[data-move]', info).forEach((btn) => btn.addEventListener('click', () => {
      lastUser = performance.now();
      playMove(b.moves[+btn.dataset.move], btn);
    }));
    bossBar(b);
  }
  langListeners.push(renderInfo);

  function rest() {
    if (!current) return;
    if (entry.loop) {
      current.play(entry.loop.map((p) => ({ pose: p, ms: 260, hold: 20 })), () => { if (!current.anim) rest(); });
    } else current.play([{ pose: current.restName, ms: 650, hold: 0 }]);
  }

  let activeBtn = null;
  function playMove(mv, btn) {
    if (!current) return;
    const steps = mv.p.filter((p) => current.poses[p]).map((p) => ({ pose: p, ...timing(p) }));
    if (!steps.length) return;
    if (activeBtn) activeBtn.classList.remove('on');
    activeBtn = btn || null;
    if (activeBtn) activeBtn.classList.add('on');
    steps.push({ pose: current.restName, ms: 700, hold: 200 });
    const model = current;
    current.play(steps, () => {
      if (activeBtn) activeBtn.classList.remove('on');
      activeBtn = null;
      if (model === current) rest();
    });
  }

  let selectToken = 0;
  async function select(i) {
    const b = BOSSES[i];
    entry = b;
    if (view.everFramed) { try { history.replaceState(null, '', '#boss-' + b.id); } catch (e) { /* file:// */ } }
    $$('.hb-slot').forEach((s, j) => { s.classList.toggle('sel', j === i); s.setAttribute('aria-selected', String(j === i)); });
    renderInfo();
    const token = ++selectToken;
    $('#boss-loading').hidden = false;
    const m = await bossModel(b);
    if (token !== selectToken) return;
    $('#boss-loading').hidden = true;
    if (current) holder.remove(current.root);
    current = m;
    current.setPose(current.restName);
    holder.add(current.root);
    holder.rotation.y = 0;
    frameModel(current, !view.everFramed);
    view.everFramed = true;
    rest();
  }

  // Hotbar
  const hotbar = $('#hotbar');
  BOSSES.forEach((b, i) => {
    const slot = document.createElement('button');
    slot.className = 'hb-slot';
    slot.type = 'button';
    slot.setAttribute('role', 'tab');
    slot.innerHTML = `<img alt="" hidden><span class="hb-num">${i < 9 ? i + 1 : ''}</span>`;
    slot.title = t(b.name);
    slot.addEventListener('click', () => { lastUser = performance.now(); select(i); });
    hotbar.appendChild(slot);
  });
  langListeners.push(() => $$('.hb-slot').forEach((s, i) => { s.title = t(BOSSES[i].name); s.setAttribute('aria-label', t(BOSSES[i].name)); }));
  document.addEventListener('keydown', (e) => {
    if (e.target.closest('input, textarea') || e.metaKey || e.ctrlKey || e.altKey) return;
    const n = parseInt(e.key, 10);
    if (n >= 1 && n <= 9 && n <= BOSSES.length) { lastUser = performance.now(); select(n - 1); }
  });

  $('#btn-rotate').addEventListener('click', (e) => {
    controls.autoRotate = !controls.autoRotate;
    e.currentTarget.setAttribute('aria-pressed', String(controls.autoRotate));
  });
  $('#btn-reset').addEventListener('click', () => { if (current) frameModel(current, false); });
  controls.addEventListener('start', () => { lastUser = performance.now(); });

  view.frame = (now, dt) => {
    if (frameTo) {
      const k = Math.min(1, (now - frameTo.t0) / 600);
      const e = k * k * (3 - 2 * k);
      controls.target.lerpVectors(frameTo.fromT, frameTo.tgt, e);
      camera.position.lerpVectors(frameTo.fromP, frameTo.pos, e);
      if (k >= 1) frameTo = null;
    }
    controls.update();
    if (current) {
      current.update(now);
      const s = reduceMotion ? 0 : now / 1000;
      holder.position.y = entry.hover ? (Math.sin(s * 1.6) * 0.5 + 0.5) * entry.hover * current.size.y : 0;
      // Ohne Eingabe spielt alle paar Sekunden ein zufälliger Angriff
      if (!reduceMotion && !current.anim && !entry.loop && now - lastUser > 7000) {
        lastUser = now;
        const mv = entry.moves[Math.floor(Math.random() * entry.moves.length)];
        playMove(mv, null);
      } else if (!reduceMotion && entry.loop && now - lastUser > 9000 && !activeBtn) {
        lastUser = now;
        playMove(entry.moves[Math.floor(Math.random() * entry.moves.length)], null);
      }
    }
    rune.rotation.z += dt * 0.25;
    rune.material.opacity = 0.35 + Math.sin(now / 500) * 0.15;
    motes.userData.tick(dt, now);
  };

  const fromHash = BOSSES.findIndex((b) => location.hash === '#boss-' + b.id);
  await select(fromHash >= 0 ? fromHash : 0);
  if (fromHash >= 0) $('#bosse').scrollIntoView();

  // Vorschaubilder für die Hotbar, eins nach dem anderen
  const thumbR = new THREE.WebGLRenderer({ antialias: true, alpha: true, preserveDrawingBuffer: true });
  thumbR.setSize(128, 128);
  thumbR.outputColorSpace = THREE.SRGBColorSpace;
  const tScene = new THREE.Scene();
  tScene.add(new THREE.HemisphereLight(0xffffff, 0x302030, 1.6));
  const tl = new THREE.DirectionalLight(0xffffff, 1.4); tl.position.set(-2, 4, 5); tScene.add(tl);
  const tCam = new THREE.PerspectiveCamera(30, 1, 0.05, 100);
  for (let i = 0; i < BOSSES.length; i++) {
    const b = BOSSES[i];
    const m = await bossModel(b);
    const wasCurrent = m === current;
    const parent = m.root.parent;
    const g = new THREE.Group();
    g.add(m.root);
    g.rotation.y = -0.55;
    tScene.add(g);
    const prev = m.current;
    m._apply(m.target(m.restName));
    const h = m.size.y, w = Math.max(m.size.x, m.size.z);
    const span = Math.max(h, w);
    const d = (span * 0.56) / Math.tan(THREE.MathUtils.degToRad(15)) + w * 0.2;
    tCam.position.set(0, h * 0.5 + span * 0.08, d);
    tCam.lookAt(0, h * 0.5, 0);
    thumbR.render(tScene, tCam);
    const img = $$('.hb-slot img')[i];
    img.src = thumbR.domElement.toDataURL();
    img.hidden = false;
    tScene.remove(g);
    g.remove(m.root);
    if (parent) parent.add(m.root);
    if (wasCurrent || parent) m._apply(prev);
  }
  thumbR.dispose();
}

// ============================================================ Start

setLang(lang);
initHero().catch((e) => console.error(e));
$$('.item-canvas').forEach((c) => initItemShowcase(c).catch((e) => console.error(e)));

let bestiaryStarted = false;
function startBestiary() {
  if (bestiaryStarted) return;
  bestiaryStarted = true;
  initBestiary().catch((e) => { console.error(e); $('#boss-loading').textContent = 'WebGL?'; });
}
if (location.hash.startsWith('#boss-')) startBestiary();
new IntersectionObserver((es, obs) => {
  if (es[0].isIntersecting) { obs.disconnect(); startBestiary(); }
}, { rootMargin: '600px' }).observe($('#bosse'));
