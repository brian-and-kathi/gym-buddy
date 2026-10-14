// Repairs text that was UTF-8 but got decoded as Latin-1 / Windows-1252
// somewhere along the way ("Â°" -> "°", "â€™" -> "’", "Ã©" -> "é").
const CP1252: Record<string, number> = {
  "€": 0x80, "‚": 0x82, "ƒ": 0x83, "„": 0x84, "…": 0x85, "†": 0x86, "‡": 0x87, "ˆ": 0x88,
  "‰": 0x89, "Š": 0x8a, "‹": 0x8b, "Œ": 0x8c, "Ž": 0x8e, "‘": 0x91, "’": 0x92, "“": 0x93,
  "”": 0x94, "•": 0x95, "–": 0x96, "—": 0x97, "˜": 0x98, "™": 0x99, "š": 0x9a, "›": 0x9b,
  "œ": 0x9c, "ž": 0x9e, "Ÿ": 0x9f,
};
const CONT = "\\u0080-\\u00bf€‚ƒ„…†‡ˆ‰Š‹ŒŽ‘’“”•–—˜™š›œžŸ";
const RUN = new RegExp(`[\\u00c2-\\u00f4][${CONT}]{1,3}`, "g");
const decoder = new TextDecoder("utf-8", { fatal: true });

function toByte(ch: string): number | null {
  const code = ch.charCodeAt(0);
  if (code <= 0xff) return code;
  return CP1252[ch] ?? null;
}

function repairRun(run: string): string {
  const bytes: number[] = [];
  for (const ch of run) {
    const b = toByte(ch);
    if (b === null) return run;
    bytes.push(b);
  }
  try {
    return decoder.decode(new Uint8Array(bytes));
  } catch {
    return run;
  }
}

export function hasMojibake(text: string): boolean {
  return fixMojibake(text) !== text;
}

export function fixMojibake(text: string): string {
  let prev = "";
  let out = text;
  for (let i = 0; i < 3 && prev !== out; i++) {
    prev = out;
    out = out.replace(RUN, repairRun);
  }
  return out;
}
