import { fixMojibake } from "./mojibake";
import { asIso, ready, sql } from "./db";
import { json, readJsonBody } from "./http";

export const DAY_NAMES = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"] as const;
const RATINGS = new Set(["easy", "good", "hard"]);
const ID_RE = /^[a-z0-9][a-z0-9-]{0,62}$/;
const MAX_EXERCISES = 40;

export type RoutineExercise = {
  title: string;
  weight: number;
  reps: number;
  sets: number;
  notes: string;
  easyGoodOrHard: string;
};

export type RoutineDay = {
  dayOfWeek: string;
  exercises: RoutineExercise[];
};

export type RoutineRecord = {
  id: string;
  name: string;
  updatedAt: string;
  days: RoutineDay[];
};

export function isRoutineId(id: string): boolean {
  return ID_RE.test(id);
}

export async function listRoutines(userId: string): Promise<Response> {
  const routine = await readUserRoutine(userId);
  return json({
    routine,
    routines: routine
      ? [{ id: routine.id, name: routine.name, updatedAt: routine.updatedAt }]
      : [],
  });
}

export async function getRoutine(userId: string, _id: string): Promise<Response> {
  const routine = await readUserRoutine(userId);
  if (!routine) return json({ error: "not found" }, 404);
  return json(routine);
}

async function readUserRoutine(userId: string): Promise<RoutineRecord | null> {
  await ready();
  const rows = await sql()`
    SELECT id, name, updated_at, days
    FROM routines
    WHERE user_id = ${userId}
    ORDER BY updated_at DESC
    LIMIT 1
  `;
  const row = rows[0];
  if (!row) return null;
  const days = parseDays(row.days);
  if (!days) return null;
  return {
    id: String(row.id),
    name: String(row.name),
    updatedAt: asIso(row.updated_at),
    days,
  };
}

export async function saveRoutine(userId: string, request: Request, pathId: string | null): Promise<Response> {
  await ready();
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  if (!parsed.value || typeof parsed.value !== "object" || Array.isArray(parsed.value)) {
    return json({ error: "body must be an object" }, 400);
  }
  const body = parsed.value as Record<string, unknown>;
  const daysResult = normalizeDays(body.days ?? body.routine);
  if (!daysResult.ok) return json({ error: daysResult.error }, 400);

  const suppliedName = typeof body.name === "string" ? body.name.trim() : "";
  const name = suppliedName ? suppliedName.slice(0, 80) : "Routine";
  const existing = await sql()`
    SELECT id FROM routines WHERE user_id = ${userId} ORDER BY updated_at DESC LIMIT 1
  `;
  const id = existing.length > 0 ? String(existing[0].id) : userId;
  const record: RoutineRecord = {
    id,
    name,
    updatedAt: new Date().toISOString(),
    days: daysResult.days,
  };
  if (existing.length > 0) {
    await sql()`
      UPDATE routines
      SET name = ${record.name}, updated_at = ${record.updatedAt}, days = ${JSON.stringify(record.days)}::jsonb
      WHERE user_id = ${userId} AND id = ${id}
    `;
    await sql()`DELETE FROM routines WHERE user_id = ${userId} AND id <> ${id}`;
  } else {
    await sql()`
      INSERT INTO routines (id, user_id, name, updated_at, days)
      VALUES (${record.id}, ${userId}, ${record.name}, ${record.updatedAt}, ${JSON.stringify(record.days)}::jsonb)
    `;
  }
  console.log(JSON.stringify({ message: "routine stored", userId, replaced: existing.length > 0 }));
  return json(record, existing.length > 0 ? 200 : 201);
}

export async function deleteRoutine(userId: string, _id: string): Promise<Response> {
  await ready();
  const rows = await sql()`
    DELETE FROM routines WHERE user_id = ${userId} RETURNING id
  `;
  if (rows.length === 0) return json({ error: "not found" }, 404);
  console.log(JSON.stringify({ message: "routine deleted", userId }));
  return json({ ok: true });
}

function parseDays(value: unknown): RoutineDay[] | null {
  if (typeof value === "string") {
    try {
      return parseDays(JSON.parse(value) as unknown);
    } catch {
      return null;
    }
  }
  if (!Array.isArray(value)) return null;
  return value as RoutineDay[];
}

export function normalizeDays(
  input: unknown,
): { ok: true; days: RoutineDay[] } | { ok: false; error: string } {
  if (!Array.isArray(input)) {
    return { ok: false, error: "routine must be an array of 7 days" };
  }
  if (input.length !== 7) {
    return { ok: false, error: "routine must contain exactly 7 days (Sun–Sat)" };
  }

  const byName = new Map<string, RoutineDay>();
  for (const item of input) {
    if (!item || typeof item !== "object") {
      return { ok: false, error: "each day must be an object" };
    }
    const raw = item as Record<string, unknown>;
    const dayName = normalizeDayName(raw.dayOfWeek);
    if (!dayName) {
      return { ok: false, error: "dayOfWeek must be Sun, Mon, Tue, Wed, Thu, Fri, or Sat" };
    }
    if (byName.has(dayName)) {
      return { ok: false, error: `duplicate day: ${dayName}` };
    }
    const exercisesRaw = raw.exercises;
    if (exercisesRaw !== undefined && !Array.isArray(exercisesRaw)) {
      return { ok: false, error: `${dayName}: exercises must be an array` };
    }
    const exercises: RoutineExercise[] = [];
    for (const exercise of (exercisesRaw as unknown[]) ?? []) {
      const parsed = normalizeExercise(exercise);
      if (!parsed.ok) return { ok: false, error: `${dayName}: ${parsed.error}` };
      exercises.push(parsed.exercise);
    }
    if (exercises.length > MAX_EXERCISES) {
      return { ok: false, error: `${dayName}: too many exercises (max ${MAX_EXERCISES})` };
    }
    byName.set(dayName, { dayOfWeek: dayName, exercises });
  }

  const days: RoutineDay[] = [];
  for (const name of DAY_NAMES) {
    const day = byName.get(name);
    if (!day) return { ok: false, error: `missing ${name}` };
    days.push(day);
  }
  return { ok: true, days };
}

function normalizeDayName(value: unknown): string | null {
  if (typeof value === "number" && value >= 1 && value <= 7) {
    return DAY_NAMES[value - 1];
  }
  if (typeof value !== "string") return null;
  const short = value.trim().slice(0, 3);
  return DAY_NAMES.find((name) => name.toLowerCase() === short.toLowerCase()) ?? null;
}

function normalizeExercise(
  value: unknown,
): { ok: true; exercise: RoutineExercise } | { ok: false; error: string } {
  if (!value || typeof value !== "object") {
    return { ok: false, error: "exercise must be an object" };
  }
  const raw = value as Record<string, unknown>;
  const title = typeof raw.title === "string" ? fixMojibake(raw.title).trim() : "";
  if (!title) return { ok: false, error: "title is required" };
  const weight = asInt(raw.weight, 0);
  const reps = asInt(raw.reps, 10);
  const sets = asInt(raw.sets, 3);
  if (weight === null || reps === null || sets === null) {
    return { ok: false, error: `${title}: weight, reps, and sets must be numbers` };
  }
  if (weight < 0 || reps < 0 || sets < 0) {
    return { ok: false, error: `${title}: weight, reps, and sets cannot be negative` };
  }
  // Guard: repair UTF-8-read-as-Latin-1 text ("Â°") so it is never stored again.
  const notes = typeof raw.notes === "string" ? fixMojibake(raw.notes) : "";
  const ratingRaw =
    typeof raw.easyGoodOrHard === "string"
      ? raw.easyGoodOrHard
      : typeof raw.rating === "string"
        ? raw.rating
        : "good";
  const easyGoodOrHard = RATINGS.has(ratingRaw.toLowerCase()) ? ratingRaw.toLowerCase() : "good";
  return { ok: true, exercise: { title, weight, reps, sets, notes, easyGoodOrHard } };
}

function asInt(value: unknown, fallback: number): number | null {
  if (value === undefined || value === null || value === "") return fallback;
  if (typeof value === "number" && Number.isFinite(value)) return Math.trunc(value);
  if (typeof value === "string" && Number.isFinite(Number(value))) return Math.trunc(Number(value));
  return null;
}
