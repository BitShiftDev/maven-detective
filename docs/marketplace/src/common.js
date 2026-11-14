// Wiederkehrende Teile der Marketplace-Bilder: Logo im Kopf, Titelleiste und Seitenleisten der IDE.
const LOGO = '<svg viewBox="0 0 40 40"><defs><linearGradient id="lg" gradientUnits="userSpaceOnUse" x1="2" y1="2" x2="38" y2="38"><stop offset="0" stop-color="#5EA1FE"/><stop offset="1" stop-color="#9D75F8"/></linearGradient></defs><rect x="2" y="2" width="36" height="36" rx="9" fill="url(#lg)"/><g fill="none" stroke="#0D1117" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"><path d="M9 7.5 H20 L26 13.5 V18 M9 7.5 V31.5 H19"/><path d="M20 7.5 V13.5 H26"/><path d="M13.5 18 L11.5 20.5 L13.5 23 M18 18 L20 20.5 L18 23 M16.6 17.4 L14.9 23.6" stroke-width="1.6"/></g><circle cx="27" cy="25" r="5" fill="#fff" fill-opacity=".25"/><g fill="none" stroke="#0D1117" stroke-linecap="round"><circle cx="27" cy="25" r="5" stroke-width="2.4"/><line x1="30.7" y1="28.7" x2="33.5" y2="31.5" stroke-width="3"/></g></svg>';
const TOOL = '<svg viewBox="0 0 16 16"><circle cx="6.75" cy="6.75" r="4.75" fill="none" stroke="#CED0D6" stroke-width="1.2"/><line x1="10.3" y1="10.3" x2="14" y2="14" stroke="#CED0D6" stroke-width="1.6" stroke-linecap="round"/><path d="M4.5 6.9 L6.1 8.5 L9.1 5.2" fill="none" stroke="#F2884B" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
const BRANCH = '<svg width="14" height="14" viewBox="0 0 16 16"><circle cx="4" cy="3.5" r="1.8" fill="none" stroke="#A8ADBD" stroke-width="1.3"/><circle cx="4" cy="12.5" r="1.8" fill="none" stroke="#A8ADBD" stroke-width="1.3"/><circle cx="12" cy="5" r="1.8" fill="none" stroke="#A8ADBD" stroke-width="1.3"/><path d="M4 5.3v5.4M12 6.8c0 3-8 2-8 4" fill="none" stroke="#A8ADBD" stroke-width="1.3"/></svg>';
const PLAY = '<svg width="16" height="16" viewBox="0 0 16 16"><path d="M4 2.5v11l9-5.5z" fill="#5FB865"/></svg>';
const DEBUG = '<svg width="16" height="16" viewBox="0 0 16 16"><rect x="4" y="5" width="8" height="9" rx="3" fill="none" stroke="#A8ADBD" stroke-width="1.3"/><path d="M2 8h2M12 8h2M2 12h2M12 12h2M6 5V3M10 5V3" stroke="#A8ADBD" stroke-width="1.3"/></svg>';
const FOLDER = '<svg viewBox="0 0 16 16"><path d="M1.5 3.5h5l1.5 1.5h6.5v8h-13z" fill="none" stroke="#DFE1E5" stroke-width="1.2" stroke-linejoin="round"/></svg>';

document.querySelectorAll('.kicker').forEach(k => k.insertAdjacentHTML('afterbegin', LOGO));
document.querySelectorAll('.titlebar').forEach(t => {
  t.innerHTML = '<div class="proj"><i>SP</i><span class="chev">shop-platform</span></div>' +
    '<div class="dim">' + BRANCH + '<span class="chev">main</span></div><div class="spacer"></div>' +
    '<div class="run"><span class="chev">' + (t.dataset.run || 'shop-platform [verify]') + '</span>' + PLAY + DEBUG + '</div>';
});
document.querySelectorAll('.stripe.left').forEach(s => {
  if (!s.innerHTML.trim()) s.innerHTML = '<div class="ic on">' + FOLDER + '</div><div class="ic"><b></b></div><div class="ic"><b style="border-radius:50%"></b></div>';
});
document.querySelectorAll('.stripe.right').forEach(s => {
  if (!s.innerHTML.trim()) s.innerHTML = '<div class="ic"><b></b></div><div class="ic' + (s.dataset.on ? ' on' : '') + '">' + TOOL + '</div><div class="ic"><b style="border-radius:50%"></b></div>';
});
