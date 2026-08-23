// STATUS.md 锚点与阶段看板的单一解析器：check-repo-health（R-GOV-04）与
// scripts/sync-status 共用，避免两处解析口径漂移（QG-4 单一实现路径）。
import fs from 'node:fs';
import path from 'node:path';

const ANCHOR_RE = /FLEXFORGE_STATUS:BEGIN([\s\S]*?)FLEXFORGE_STATUS:END/;

function grab(anchor, key) {
  return (anchor.match(new RegExp(`${key}: *(.*)`)) || [])[1]?.trim();
}

export function parseAnchor(text) {
  const match = text.match(ANCHOR_RE);
  if (!match) {
    throw new Error('STATUS.md 缺少 FLEXFORGE_STATUS 锚点块');
  }
  const anchor = match[1];
  const blockersRaw = grab(anchor, 'BLOCKERS') ?? 'none';
  const blockers = blockersRaw === 'none' || blockersRaw === ''
    ? []
    : blockersRaw.split(';').map((s) => s.trim()).filter(Boolean);
  const progress = grab(anchor, 'PROJECT_PROGRESS')?.replace('%', '') ?? '0';
  return {
    currentStageId: grab(anchor, 'CURRENT_STAGE_ID'),
    currentStageName: grab(anchor, 'CURRENT_STAGE_NAME'),
    status: grab(anchor, 'STAGE_STATUS'),
    progressPercent: Number.parseInt(progress, 10),
    updatedAt: grab(anchor, 'LAST_UPDATED'),
    owner: grab(anchor, 'OWNER'),
    nextAction: grab(anchor, 'NEXT_ACTION'),
    exitGate: grab(anchor, 'EXIT_GATE'),
    blockers,
  };
}

export function parseStageBoard(text) {
  const board = text.match(/## 阶段看板([\s\S]*?)(?=\n## |$)/)?.[1] ?? '';
  const stages = [];
  for (const line of board.split('\n')) {
    const row = line.match(/^\|\s*(P\d+)\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|/);
    if (row) {
      stages.push({ id: row[1], name: row[2].trim(), status: row[3].trim() });
    }
  }
  if (stages.length === 0) {
    throw new Error('STATUS.md 阶段看板没有可解析的阶段行');
  }
  return stages;
}

export function readStatusSnapshot(root) {
  const text = fs.readFileSync(path.join(root, 'STATUS.md'), 'utf8');
  return {
    ...parseAnchor(text),
    stages: parseStageBoard(text),
  };
}

export function buildStatusJson(snapshot) {
  return {
    project: 'FlexForge',
    updatedAt: snapshot.updatedAt,
    currentStageId: snapshot.currentStageId,
    currentStageName: snapshot.currentStageName,
    status: snapshot.status,
    progressPercent: snapshot.progressPercent,
    owner: snapshot.owner,
    nextAction: snapshot.nextAction,
    exitGate: snapshot.exitGate,
    blockers: snapshot.blockers,
    stages: snapshot.stages,
  };
}
