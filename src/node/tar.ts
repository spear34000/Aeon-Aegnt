import { gunzipSync } from "node:zlib";

export interface TarLimits {
  /** Largest total size of all files together. */
  maxBytes: number;
  maxFiles: number;
}

export interface Snapshot {
  /** Regular files by relative path with `/` separators; the archive's single top folder is removed. */
  files: Map<string, Buffer>;
  /** The commit id GitHub writes into the archive header, when there is one. */
  commit?: string;
  /** Entries left out (links, odd paths) so a person can be told. */
  skipped: string[];
}

const cstr = (b: Buffer, from: number, len: number): string => {
  const slice = b.subarray(from, from + len);
  const end = slice.indexOf(0);
  return slice.subarray(0, end < 0 ? len : end).toString("utf8");
};

function octal(b: Buffer, from: number, len: number): number {
  const s = cstr(b, from, len).trim();
  if (b[from]! & 0x80) throw new Error("archive uses a size format that is not supported");
  return s === "" ? 0 : parseInt(s, 8);
}

/** pax extended header records: `<length> <key>=<value>\n`, the length counting the whole record in bytes. */
function paxRecords(data: Buffer): Record<string, string> {
  const out: Record<string, string> = {};
  let i = 0;
  while (i < data.length) {
    const sp = data.indexOf(0x20, i);
    if (sp < 0) break;
    const len = parseInt(data.subarray(i, sp).toString("ascii"), 10);
    if (!Number.isFinite(len) || len <= 0 || i + len > data.length) break;
    const rec = data.subarray(sp + 1, i + len - 1).toString("utf8");
    const eq = rec.indexOf("=");
    if (eq > 0) out[rec.slice(0, eq)] = rec.slice(eq + 1);
    i += len;
  }
  return out;
}

/** A path safe to keep as a map key: relative, forward slashes, no `..`, no empty parts, no control characters. */
export function safeRelativePath(p: string): string | null {
  if (p.length === 0 || p.length > 240 || /[\u0000-\u001f\\]/.test(p) || p.startsWith("/") || /^[A-Za-z]:/.test(p)) return null;
  const parts = p.split("/").filter((x, i, a) => !(x === "" && i === a.length - 1));
  if (parts.length === 0 || parts.length > 12) return null;
  for (const x of parts) if (x === "" || x === "." || x === ".." ) return null;
  return parts.join("/");
}

/**
 * Reads a tar archive (ustar, pax and GNU long names) into memory. Only regular files are kept: links, devices and
 * anything with a path that could leave the folder are skipped. The sizes are checked while reading, so a small
 * archive that claims huge contents is refused before it is expanded.
 */
export function readTar(tar: Buffer, lim: TarLimits, stripTop: boolean): Snapshot {
  const files = new Map<string, Buffer>();
  const skipped: string[] = [];
  let commit: string | undefined;
  let total = 0;
  let pax: Record<string, string> = {};
  let longName: string | null = null;
  let off = 0;
  while (off + 512 <= tar.length) {
    const h = tar.subarray(off, off + 512);
    if (h.every((x) => x === 0)) break;
    let sum = 0;
    for (let i = 0; i < 512; i++) sum += i >= 148 && i < 156 ? 32 : h[i]!;
    if (sum !== octal(h, 148, 8)) throw new Error("archive is damaged (bad header checksum)");
    const size = octal(h, 124, 12);
    const type = String.fromCharCode(h[156] === 0 ? 48 : h[156]!);
    const dataStart = off + 512;
    if (size < 0 || dataStart + size > tar.length) throw new Error("archive is damaged (a file runs past the end)");
    const data = tar.subarray(dataStart, dataStart + size);
    off = dataStart + Math.ceil(size / 512) * 512;
    if (type === "g") {
      const g = paxRecords(data);
      if (g.comment && /^[0-9a-f]{40}$/i.test(g.comment)) commit = g.comment.toLowerCase();
      continue;
    }
    if (type === "x") {
      pax = paxRecords(data);
      continue;
    }
    if (type === "L") {
      longName = cstr(data, 0, data.length);
      continue;
    }
    const prefix = cstr(h, 345, 155);
    let name = pax.path ?? longName ?? (prefix ? `${prefix}/${cstr(h, 0, 100)}` : cstr(h, 0, 100));
    pax = {};
    longName = null;
    if (type === "5") continue;
    if (type !== "0") {
      skipped.push(`${name.slice(0, 80)} (${type === "2" || type === "1" ? "link" : "special file"})`);
      continue;
    }
    if (name.startsWith("/") || /^[A-Za-z]:/.test(name)) {
      skipped.push(`${name.slice(0, 80)} (unsafe path)`);
      continue;
    }
    if (stripTop) {
      const i = name.indexOf("/");
      if (i < 0) continue;
      name = name.slice(i + 1);
    }
    const rel = safeRelativePath(name);
    if (!rel) {
      if (name) skipped.push(`${name.slice(0, 80)} (unsafe path)`);
      continue;
    }
    total += size;
    if (total > lim.maxBytes) throw new Error(`archive holds more than ${Math.round(lim.maxBytes / 1_000_000)} MB`);
    if (files.size >= lim.maxFiles) throw new Error(`archive holds more than ${lim.maxFiles} files`);
    files.set(rel, Buffer.from(data));
  }
  return { files, ...(commit ? { commit } : {}), skipped };
}

/** A `.tar.gz` such as GitHub's. The unpacked size is capped while it is inflated. */
export function readTarGz(gz: Buffer, lim: TarLimits, stripTop = true): Snapshot {
  let tar: Buffer;
  try {
    tar = gunzipSync(gz, { maxOutputLength: lim.maxBytes + lim.maxFiles * 1024 + 1_048_576 });
  } catch (e) {
    const code = (e as NodeJS.ErrnoException).code;
    throw new Error(code === "ERR_BUFFER_TOO_LARGE" ? `archive is larger than ${Math.round(lim.maxBytes / 1_000_000)} MB when unpacked` : "not a valid .tar.gz");
  }
  return readTar(tar, lim, stripTop);
}
