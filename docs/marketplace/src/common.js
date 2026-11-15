// Logo in den Kopf setzen und Screenshots zuschneiden.
// <div class="shot" data-crop="x,y,w,h"><img src="../shots/…"></div> zeigt den Ausschnitt (Pixel im Original),
// so groß wie in die umgebende .fit passt.
const LOGO = '<svg viewBox="0 0 40 40"><defs><linearGradient id="lg" gradientUnits="userSpaceOnUse" x1="2" y1="2" x2="38" y2="38"><stop offset="0" stop-color="#5EA1FE"/><stop offset="1" stop-color="#9D75F8"/></linearGradient></defs><rect x="2" y="2" width="36" height="36" rx="9" fill="url(#lg)"/><g fill="none" stroke="#0D1117" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"><path d="M9 7.5 H20 L26 13.5 V18 M9 7.5 V31.5 H19"/><path d="M20 7.5 V13.5 H26"/><path d="M13.5 18 L11.5 20.5 L13.5 23 M18 18 L20 20.5 L18 23 M16.6 17.4 L14.9 23.6" stroke-width="1.6"/></g><circle cx="27" cy="25" r="5" fill="#fff" fill-opacity=".25"/><g fill="none" stroke="#0D1117" stroke-linecap="round"><circle cx="27" cy="25" r="5" stroke-width="2.4"/><line x1="30.7" y1="28.7" x2="33.5" y2="31.5" stroke-width="3"/></g></svg>';
document.querySelectorAll('.kicker').forEach(k => k.insertAdjacentHTML('afterbegin', LOGO));

// Mehrere .shot in einer .fit stehen nebeneinander (.fit.col: untereinander) im selben Maßstab.
const GAP = 22;
document.querySelectorAll('.fit').forEach(area => {
  const shots = [...area.querySelectorAll('.shot')].map(box => ({ box, img: box.querySelector('img'),
    c: box.dataset.crop.split(',').map(Number) }));
  const place = () => {
    if (shots.some(s => !s.img.naturalWidth)) return;
    const col = area.classList.contains('col');
    const gaps = GAP * (shots.length - 1);
    const width = col ? Math.max(...shots.map(s => s.c[2])) : shots.reduce((t, s) => t + s.c[2], 0);
    const height = col ? shots.reduce((t, s) => t + s.c[3], 0) : Math.max(...shots.map(s => s.c[3]));
    const k = Math.min((area.clientWidth - (col ? 0 : gaps)) / width, (area.clientHeight - (col ? gaps : 0)) / height);
    shots.forEach(({ box, img, c: [x, y, w, h] }) => {
      box.style.width = Math.round(w * k) + 'px';
      box.style.height = Math.round(h * k) + 'px';
      img.style.width = img.naturalWidth * k + 'px';
      img.style.left = -x * k + 'px';
      img.style.top = -y * k + 'px';
    });
  };
  shots.forEach(s => s.img.complete ? place() : s.img.addEventListener('load', place));
});
