/**
 * BitChord Audio: the stylesheet, made readable by an old Chromium (the LG TV
 * build). Tailwind v4 writes CSS for Chrome 111+: cascade layers (Chrome 99),
 * registered custom properties (@property, Chrome 85) and oklch()/color-mix()
 * colours (Chrome 111). The TV's Chromium may be 79, which drops a whole
 * @layer block and every rule using an unknown colour. So, after the build:
 *
 * - layers are flattened (@csstools/postcss-cascade-layers keeps their order
 *   through specificity);
 * - each @property's initial value is set on every element, which is what
 *   Tailwind's variables rely on;
 * - oklch() becomes rgb(), and color-mix(in oklab, C P%, transparent) — how
 *   Tailwind writes "/50" opacity — becomes C at that alpha.
 */
import cascadeLayers from '@csstools/postcss-cascade-layers';
import postcss, { type AtRule, type Plugin as PostcssPlugin } from 'postcss';
import type { Plugin } from 'vite';

function clamp(v: number): number {
  return Math.min(255, Math.max(0, Math.round(v)));
}

/** oklch → sRGB, per CSS Color 4. */
function oklchToRgb(l: number, c: number, hDeg: number): [number, number, number] {
  const h = (hDeg * Math.PI) / 180;
  const a = c * Math.cos(h);
  const b = c * Math.sin(h);
  const l_ = l + 0.3963377774 * a + 0.2158037573 * b;
  const m_ = l - 0.1055613458 * a - 0.0638541728 * b;
  const s_ = l - 0.0894841775 * a - 1.291485548 * b;
  const L = l_ ** 3;
  const M = m_ ** 3;
  const S = s_ ** 3;
  const lin = [
    4.0767416621 * L - 3.3077115913 * M + 0.2309699292 * S,
    -1.2684380046 * L + 2.6097574011 * M - 0.3413193965 * S,
    -0.0041960863 * L - 0.7034186147 * M + 1.707614701 * S,
  ];
  const gamma = (x: number) => (x <= 0.0031308 ? 12.92 * x : 1.055 * Math.pow(Math.max(x, 0), 1 / 2.4) - 0.055);
  return lin.map((x) => clamp(gamma(x) * 255)) as [number, number, number];
}

function num(token: string, percentScale = 1): number {
  const t = token.trim();
  if (t === 'none') return 0;
  if (t.endsWith('%')) return (parseFloat(t) / 100) * percentScale;
  return parseFloat(t);
}

const OKLCH = /oklch\(\s*([^\s/)]+)\s+([^\s/)]+)\s+([^\s/)]+)\s*(?:\/\s*([^)]+))?\)/g;

function lowerOklch(value: string): string {
  return value.replace(OKLCH, (_m, l: string, c: string, h: string, alpha?: string) => {
    const [r, g, b] = oklchToRgb(num(l, 1), num(c, 0.4), num(h));
    if (alpha === undefined) return `rgb(${r}, ${g}, ${b})`;
    const a = alpha.trim().endsWith('%') ? parseFloat(alpha) / 100 : parseFloat(alpha);
    return `rgba(${r}, ${g}, ${b}, ${Number.isFinite(a) ? a : 1})`;
  });
}

/** color-mix(in oklab|srgb, <rgb()> P%, transparent) → rgba(…, P). */
const MIX = /color-mix\(\s*in\s+[a-z]+\s*,\s*(rgba?\([^)]*\)|#[0-9a-fA-F]{3,8})\s+([\d.]+)%\s*,\s*transparent\s*\)/g;

function hexToRgb(hex: string): [number, number, number, number] {
  let h = hex.slice(1);
  if (h.length === 3 || h.length === 4) h = h.split('').map((c) => c + c).join('');
  const v = (i: number) => parseInt(h.slice(i, i + 2), 16);
  return [v(0), v(2), v(4), h.length === 8 ? v(6) / 255 : 1];
}

function lowerMix(value: string): string {
  return value.replace(MIX, (_m, color: string, pct: string) => {
    let rgba: [number, number, number, number];
    if (color.startsWith('#')) rgba = hexToRgb(color);
    else {
      const parts = color.replace(/rgba?\(|\)/g, '').split(/[\s,/]+/).filter(Boolean).map(Number);
      rgba = [parts[0] ?? 0, parts[1] ?? 0, parts[2] ?? 0, parts[3] ?? 1];
    }
    return `rgba(${rgba[0]}, ${rgba[1]}, ${rgba[2]}, ${+(rgba[3] * (parseFloat(pct) / 100)).toFixed(3)})`;
  });
}

const colours: PostcssPlugin = {
  postcssPlugin: 'bitchord-old-colours',
  Declaration(decl) {
    if (!/oklch\(|color-mix\(/.test(decl.value)) return;
    const lowered = lowerMix(lowerOklch(decl.value));
    if (lowered === decl.value) return;
    // A rule an old engine can't read is dropped whole: the lowered value goes first,
    // and the original stays after it for engines that can.
    if (/oklch\(|color-mix\(/.test(lowered)) decl.cloneBefore({ value: lowered.replace(/color-mix\([^)]*\)/g, 'transparent') });
    else decl.value = lowered;
  },
};

const properties: PostcssPlugin = {
  postcssPlugin: 'bitchord-property-fallback',
  OnceExit(root) {
    const initial: string[] = [];
    root.walkAtRules('property', (rule: AtRule) => {
      let value: string | null = null;
      rule.walkDecls('initial-value', (d) => {
        value = d.value;
      });
      if (value !== null) initial.push(`${rule.params.trim()}: ${value}`);
    });
    if (!initial.length) return;
    // Lowest specificity, first in the sheet: anything that sets them wins.
    root.prepend(postcss.parse(`:where(*, ::before, ::after, ::backdrop) { ${initial.join('; ')} }`));
  },
};

export function webosCss(): Plugin {
  const lower = async (css: string) =>
    (await postcss([properties, cascadeLayers(), colours]).process(css, { from: undefined })).css;
  return {
    name: 'bitchord-webos-css',
    apply: 'build',
    enforce: 'post',
    // Each stylesheet as it is compiled (the legacy build inlines them into JS afterwards).
    async transform(code, id) {
      if (!/\.css($|\?)/.test(id) || id.includes('?raw')) return null;
      if (!/@layer|@property|oklch\(|color-mix\(/.test(code)) return null;
      // Plain CSS at this point; anything else (a JS-wrapped module) is left alone.
      if (/^\s*(export|import|const)\b/.test(code)) return null;
      return { code: await lower(code), map: null };
    },
    // Any stylesheet emitted as a file, and — the legacy build's way — the ones
    // inlined into the JS as template strings handed to a <style>.
    async generateBundle(_options, bundle) {
      for (const file of Object.values(bundle)) {
        if (file.type === 'asset' && file.fileName.endsWith('.css')) {
          const source = typeof file.source === 'string' ? file.source : new TextDecoder().decode(file.source);
          file.source = await lower(source);
          continue;
        }
        if (file.type !== 'chunk' || !/@layer|@property|color-mix\(/.test(file.code)) continue;
        // The stylesheet strings Vite hands to a <style>: `….textContent=\`…\``,
        // read to their closing backtick (skipping \`-escapes and ${…}).
        let out = '';
        let last = 0;
        const opener = /textContent\s*=\s*`/g;
        let found: RegExpExecArray | null;
        while ((found = opener.exec(file.code))) {
          const begin = found.index + found[0].length;
          // Asset URLs split the string: `…url(\`+new URL(\`x.woff2\`,t.meta.url).href+\`)…`.
          const joint = /^`\s*\+\s*(new URL\(`[^`]*`\s*,[^)]*\)\.href)\s*\+\s*`/;
          const held: string[] = [];
          let raw = '';
          let i = begin;
          let depth = 0;
          let from = begin;
          for (; i < file.code.length; i++) {
            const c = file.code[i];
            if (c === '\\') { i++; continue; }
            if (c === '$' && file.code[i + 1] === '{') { depth++; i++; continue; }
            if (depth > 0 && c === '}') { depth--; continue; }
            if (depth === 0 && c === '`') {
              const join = joint.exec(file.code.slice(i, i + 300));
              if (!join) break;
              raw += file.code.slice(from, i) + `__BCX${held.push('`+' + join[1] + '+`') - 1}__`;
              i += join[0].length - 1;
              from = i + 1;
            }
          }
          raw += file.code.slice(from, i);
          opener.lastIndex = i + 1;
          if (!/@layer|@property|color-mix\(/.test(raw)) continue;
          const tokenised = raw.replace(/\$\{[^}]*\}/g, (x) => `__BCX${held.push(x) - 1}__`);
          const css = tokenised.replace(/\\([`\\$])/g, '$1');
          let lowered: string;
          try {
            lowered = await lower(css);
          } catch (error) {
            if (process.env.WEBOS_CSS_DEBUG) console.log('webos-css failed', error);
            continue;
          }
          const escaped = lowered
            .replace(/[`\\]/g, (c) => '\\' + c)
            .replace(/\$(?=\{)/g, '\\$')
            .replace(/__BCX(\d+)__/g, (_x, n: string) => held[Number(n)] ?? '');
          out += file.code.slice(last, begin) + escaped;
          last = i;
        }
        if (last) file.code = out + file.code.slice(last);
        if (process.env.WEBOS_CSS_DEBUG) console.log('webos-css', file.fileName, last ? 'lowered' : 'untouched');
      }
    },
  };
}
