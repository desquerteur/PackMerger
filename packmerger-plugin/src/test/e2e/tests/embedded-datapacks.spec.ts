import { afterAll, describe, expect, sleep, test } from '@plugwright/runner';
import type { PlayerWrapper, ServerWrapper } from '@plugwright/runner';
import { createHash } from 'node:crypto';
import {
  closeSync, cpSync, existsSync, mkdirSync, openSync, readFileSync, readdirSync, rmSync, statSync,
  writeFileSync, writeSync,
} from 'node:fs';
import { createRequire } from 'node:module';
import { join } from 'node:path';
import {
  PACK_URL, PLUGIN_DIR, RUN_DIR, SELF_HOST_PORT, answerResourcePack, buildZip, consoleCommand, downloadPack,
  nextResourcePackOffer, offeredWithin, serverLogMark,
} from './support/pack.js';

// Datapack discovery (datapacks.* in config.yml) against real datapacks in the running world.
// (Named so it runs after distribution.spec, whose first test must be the first of the run, and
// before plugin-api.spec, which disables the PmApiTest plugin this spec uses for a second world.)
//   world/datapacks/pm-hybrid/        folder, assets/ next to data/
//   world/datapacks/pm-subfolder.zip  zip, resource pack in resourcepack/
//   world/datapacks/pm-nested.zip     zip, resource pack as resourcepack.zip inside it
//   world/datapacks/pm-dataonly/      folder, data/ only
// The sources live in fixtures/PmE2EDatapacks (staged into plugins/PmE2EDatapacks/); the world
// starts without datapacks, so the other specs never see them. The block below installs them,
// lets the server enable them (minecraft:reload) and removes them again in afterAll.
//
// The server keeps every enabled zip datapack open, and Windows refuses to delete or rewrite
// an open file. So runtime updates and removals use folder datapacks, the zips are disabled
// before they are deleted, and the half-written zip is a new one (merged with only-enabled off).

const SOURCES = `${RUN_DIR}plugins/PmE2EDatapacks`;
const DATAPACKS = `${RUN_DIR}world/datapacks`;
const CONFIG = `${PLUGIN_DIR}config.yml`;
const ZIPS = ['pm-subfolder.zip', 'pm-nested.zip'];

/** Every file under dir, keyed by its slash-separated path relative to dir. */
function readTree(dir: string, prefix = ''): Record<string, Buffer> {
  const out: Record<string, Buffer> = {};
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) Object.assign(out, readTree(path, `${prefix}${name}/`));
    else out[`${prefix}${name}`] = readFileSync(path);
  }
  return out;
}

function zipOf(dir: string, extra: Record<string, Buffer> = {}): Buffer {
  return buildZip({ ...readTree(dir), ...extra });
}

function installDatapacks(): void {
  mkdirSync(DATAPACKS, { recursive: true });
  cpSync(`${SOURCES}/hybrid`, `${DATAPACKS}/pm-hybrid`, { recursive: true });
  cpSync(`${SOURCES}/dataonly`, `${DATAPACKS}/pm-dataonly`, { recursive: true });
  writeFileSync(`${DATAPACKS}/pm-subfolder.zip`, zipOf(`${SOURCES}/subfolder`));
  writeFileSync(`${DATAPACKS}/pm-nested.zip`, zipOf(`${SOURCES}/nested`, { 'resourcepack.zip': zipOf(`${SOURCES}/nested-rp`) }));
}

async function removeDatapacks(server: ServerWrapper): Promise<void> {
  rmSync(`${DATAPACKS}/pm-partial.zip`, { force: true });
  for (const zip of ZIPS) await server.execute(`datapack disable "file/${zip}"`, 30_000);
  for (const name of ['pm-hybrid', 'pm-dataonly', 'pm-late', ...ZIPS]) {
    rmSync(`${DATAPACKS}/${name}`, { recursive: true, force: true, maxRetries: 5, retryDelay: 500 });
  }
}

async function entries(): Promise<string[]> {
  const pack = await downloadPack();
  return pack.status === 200 ? [...pack.entries.keys()] : [];
}

async function entryOrNull(path: string): Promise<string | null> {
  const pack = await downloadPack();
  const data = pack.status === 200 ? pack.entries.get(path) : undefined;
  return data ? data.toString('utf8').trim() : null;
}

async function sources(server: ServerWrapper): Promise<string> {
  return consoleCommand(server, 'pm sources datapacks');
}

/** The server's datapack reload: enables datapacks that appeared in world/datapacks. */
async function reloadDatapacks(server: ServerWrapper): Promise<void> {
  await server.execute('minecraft:reload', 30_000);
}

/** Runs a PackMerger command as the (op) player and waits for a reply line; for commands that answer asynchronously. */
async function playerCommand(player: PlayerWrapper, cmd: string, expected: string | RegExp, timeout = 20_000): Promise<void> {
  const since = player.getMessageBufferIndex();
  player.chat(cmd);
  await expect(player).toHaveReceivedMessage(expected, { since, timeout });
}

/** Rewrites config.yml with fn and applies it with /pm reload. */
async function editConfig(server: ServerWrapper, fn: (text: string) => string): Promise<void> {
  const before = readFileSync(CONFIG, 'utf8');
  const after = fn(before);
  expect(after).not.toBe(before);
  writeFileSync(CONFIG, after);
  const mark = serverLogMark(server);
  await consoleCommand(server, 'pm reload');
  await expect(server).toHaveReceivedMessage(`Pack uploaded successfully: ${PACK_URL}`, { since: mark, timeout: 20_000 });
}

/** mineflayer from the runner's own dependencies (the test workspace does not install it directly). */
function mineflayer(): any {
  return createRequire(import.meta.resolve('@plugwright/runner'))('mineflayer');
}

describe.serial('datapack resource packs', () => {
  afterAll(async ({ server }) => {
    await removeDatapacks(server);
    await consoleCommand(server, 'pmapitest unloadworld pm_second');
    await reloadDatapacks(server);
    await server.execute('pm merge');
    await sleep(3_000);
  });

  test('hybrid, subfolder and nested-zip datapacks are merged; data-only is listed; data/ never ships',
    async ({ player, server }) => {
      await player.makeOp();
      installDatapacks();
      await reloadDatapacks(server);

      await expect.poll(async () => {
        const e = await entries();
        return ['assets/pmdp/hybrid.txt', 'assets/pmdp/subfolder.txt', 'assets/pmdp/nested.txt']
          .every(p => e.includes(p));
      }, { timeout: 25_000 }).toBe(true);

      const pack = await downloadPack();
      const names = [...pack.entries.keys()];
      expect(names.filter(n => n.startsWith('data/') || n.endsWith('.mcfunction'))).toEqual([]);
      // The fixture packs are still there, and the hybrid's lang file is merged key by key.
      expect(names).toContain('assets/pmtest/marker.txt');
      const lang = JSON.parse(pack.entries.get('assets/minecraft/lang/en_us.json')!.toString('utf8'));
      expect(lang['pmdp.hybrid']).toBe('Hybrid datapack');
      expect(lang['pmtest.shared']).toBe('Shared from overlay');

      await expect(server).toHaveReceivedMessage(/\[DATAPACK\].*\[discovered\] dp-pm-hybrid/);
      await expect(server).toHaveReceivedMessage(/\[DATAPACK\].*\[discovered\] dp-pm-subfolder/);
      await expect(server).toHaveReceivedMessage(/\[DATAPACK\].*\[discovered\] dp-pm-nested/);
      await expect(server).not.toHaveReceivedMessage(/\[validation-error\]/);
    });

  test('/pm sources, /pm status, /pm scan, /pm conflicts and /pm rebuild describe the datapacks',
    async ({ player, server }) => {
      const dp = await sources(server);
      expect(dp).toContain('Datapack sources');
      expect(dp).toMatch(/dp-pm-hybrid \[datapack\] merged/);
      expect(dp).toMatch(/dp-pm-subfolder \[datapack\] merged/);
      expect(dp).toMatch(/dp-pm-nested \[datapack\] merged/);
      expect(dp).toMatch(/dp-pm-dataonly \[datapack\] data-only/);

      const all = await consoleCommand(server, 'pm sources');
      expect(all).toMatch(/base-pack \[packs\] present/);
      expect(all).toMatch(/dp-pm-hybrid \[datapack\] merged/);

      const status = await consoleCommand(server, 'pm status');
      expect(status).toContain('Datapacks: 3 merged, 1 data-only (/pm sources datapacks)');

      // hybrid and subfolder both ship assets/pmdp/shared.txt with different content.
      const conflicts = await consoleCommand(server, 'pm conflicts');
      expect(conflicts).toMatch(/Conflicts \(\d+\)/);
      expect(conflicts).toMatch(
        /assets\/pmdp\/shared\.txt winner: \[dp\] dp-pm-(hybrid|subfolder) lost: \[dp\] dp-pm-(hybrid|subfolder)/);

      await player.makeOp();
      await playerCommand(player, '/pm scan', /Datapacks: 4 found in \d+ folder\(s\) \(.*world\/datapacks/);
      await expect(player).toHaveReceivedMessage(/dp-pm-hybrid/);
      await expect(player).toHaveReceivedMessage('1 data-only datapack(s)');
      await expect(player).toHaveReceivedMessage('Nothing changed.');

      const before = (await downloadPack()).sha1;
      await playerCommand(player, '/pm rebuild', 'Rescanning datapacks and rebuilding the pack');
      await expect(player).toHaveReceivedMessage(`Merge and upload complete! URL: ${PACK_URL}`, { timeout: 20_000 });
      // Nothing changed: the rebuilt pack is byte-identical (deterministic zip), so no new pack.
      expect((await downloadPack()).sha1).toBe(before);
    });

  test('the offered pack URL uses the address the player joined with, and serves the advertised SHA-1',
    async ({ player, server }) => {
      await player.rejoin();
      const offer = await nextResourcePackOffer(player.bot);
      expect(offer.url).toBe(PACK_URL);
      const served = await downloadPack();
      expect(served.status).toBe(200);
      expect(offer.hash).toBe(served.sha1);
      expect(served.entries.has('assets/pmdp/hybrid.txt')).toBe(true);

      // A client that typed a LAN address (sent in the handshake; the TCP connection still
      // goes to localhost) gets that address in its URL, never localhost.
      const conn = (server as any).session.env.connection();
      const bot = mineflayer().createBot({ ...conn, username: 'pm_lan', fakeHost: '192.168.77.5', logErrors: false });
      bot.on('error', () => {});
      try {
        const lanOffer = await nextResourcePackOffer(bot, 20_000);
        expect(lanOffer.url).toBe(`http://192.168.77.5:${SELF_HOST_PORT}/pack`);
        expect(lanOffer.hash).toBe(served.sha1);
        // The same file behind that URL (fetched through loopback, the only interface the e2e server uses).
        const viaLoopback = Buffer.from(await (await fetch(`http://127.0.0.1:${SELF_HOST_PORT}/pack`)).arrayBuffer());
        expect(createHash('sha1').update(viaLoopback).digest('hex')).toBe(lanOffer.hash);
        await expect(server).toHaveReceivedMessage(
          `Sent resource pack to pm_lan (URL: http://192.168.77.5:${SELF_HOST_PORT}/pack, per-player)`);
      } finally {
        bot.quit();
      }
    });

  test('a datapack added at runtime is listed at once and merged once the server enables it', async ({ server }) => {
    cpSync(`${SOURCES}/late`, `${DATAPACKS}/pm-late`, { recursive: true });
    // Listed as soon as the watcher sees it; once staged it is reported as not enabled.
    await expect.poll(async () => /dp-pm-late \[datapack\] not enabled/.test(await sources(server)), { timeout: 15_000 })
      .toBe(true);
    expect(await entries()).not.toContain('assets/pmdp/late.txt');
    await reloadDatapacks(server);
    await expect.poll(async () => (await entries()).includes('assets/pmdp/late.txt'), { timeout: 15_000 }).toBe(true);
  });

  test('a datapack changed at runtime is re-merged and the new pack is offered', async ({ player, server }) => {
    await player.makeOp();
    const first = await downloadPack();
    writeFileSync(`${DATAPACKS}/pm-hybrid/assets/pmdp/hybrid.txt`, 'hybrid v2\n');
    await expect.poll(() => entryOrNull('assets/pmdp/hybrid.txt'), { timeout: 15_000 }).toBe('hybrid v2');
    await expect(server).toHaveReceivedMessage('datapacks changed');
    const latest = await downloadPack();
    expect(latest.sha1).not.toBe(first.sha1);
    // on-new-pack: notify (fixture) tells online players; a rejoin is offered the new pack.
    await expect(player).toHaveReceivedMessage('A new resource pack is available', { timeout: 10_000 });
    await player.rejoin();
    const offer = await nextResourcePackOffer(player.bot);
    expect(offer.hash).toBe(latest.sha1);
    answerResourcePack(player.bot, offer.uuid, 'accepted');
    answerResourcePack(player.bot, offer.uuid, 'loaded');
  });

  test('a datapack removed at runtime leaves the pack without any command', async () => {
    rmSync(`${DATAPACKS}/pm-late`, { recursive: true, force: true });
    await expect.poll(async () => (await entries()).includes('assets/pmdp/late.txt'), { timeout: 15_000 }).toBe(false);
    expect(await entries()).toContain('assets/pmdp/hybrid.txt');
  });

  test('a datapack zip that is still being written is not read until it is complete', async ({ server, cleanup }) => {
    // Merge datapacks the server has not enabled (yet), so the new zip is merged by itself.
    cleanup(() => editConfig(server, t => t.replace(/^( {2}only-enabled: )false/m, '$1true')));
    await editConfig(server, t => t.replace(/^( {2}only-enabled: )true/m, '$1false'));
    const filler = Buffer.alloc(512 * 1024, 'x');
    const zip = buildZip({
      'pack.mcmeta': readFileSync(`${SOURCES}/late/pack.mcmeta`),
      'data/pmdp/filler.bin': filler,
      'assets/pmdp/partial.txt': 'partial\n',
    });
    // Written the way an FTP upload does it: the final name, growing in steps.
    const mark = serverLogMark(server);
    const fd = openSync(`${DATAPACKS}/pm-partial.zip`, 'w');
    const seen: string[][] = [];
    try {
      const chunk = Math.ceil(zip.length / 10);
      for (let off = 0; off < zip.length; off += chunk) {
        writeSync(fd, zip, off, Math.min(chunk, zip.length - off));
        await sleep(300);
        seen.push(await entries());
      }
    } finally {
      closeSync(fd);
    }
    // While it grew: never in the pack, and the live pack was never replaced by a broken one.
    expect(seen.every(e => !e.includes('assets/pmdp/partial.txt') && e.includes('assets/pmdp/hybrid.txt'))).toBe(true);
    await expect.poll(() => entryOrNull('assets/pmdp/partial.txt'), { timeout: 15_000 }).toBe('partial');
    await expect(server).not.toHaveReceivedMessage(/\[validation-error\] dp-pm-partial/, { since: mark });
    expect((await entries()).filter(n => n.startsWith('data/'))).toEqual([]);
    rmSync(`${DATAPACKS}/pm-partial.zip`, { force: true });
  });

  test("a datapack in another world's folder is listed but not merged", async ({ player, server }) => {
    const reply = await consoleCommand(server, 'pmapitest world pm_second');
    const folder = /folder=(.+)$/m.exec(reply)?.[1]?.trim();
    expect(folder).toBeTruthy();
    cpSync(`${SOURCES}/elsewhere`, `${folder}/datapacks/pm-elsewhere`, { recursive: true });
    await player.makeOp();
    await playerCommand(player, '/pm scan', /Datapacks: \d+ found in 2 folder\(s\)/);
    expect(await sources(server)).toMatch(/dp-pm-elsewhere \[datapack\] (folder not loaded by the server|found)/);
    await playerCommand(player, '/pm merge', `Merge and upload complete! URL: ${PACK_URL}`);
    expect(await sources(server)).toMatch(/dp-pm-elsewhere \[datapack\] folder not loaded by the server/);
    expect(await entries()).not.toContain('assets/pmdp/elsewhere.txt');
    expect(await entries()).toContain('assets/pmdp/hybrid.txt');
    rmSync(`${folder}/datapacks/pm-elsewhere`, { recursive: true, force: true });
  });
});

// When nothing is left to merge (here: the packs/ folder emptied, no datapacks with
// assets), the previous pack must not keep being sent; it comes back with the next merge.
test('a merge with no resource packs left withdraws the pack until one is merged again',
  async ({ player, server, cleanup }) => {
    const packs = `${PLUGIN_DIR}packs`;
    const parked = `${RUN_DIR}e2e-parked`;
    const restore = async () => {
      for (const name of ['base-pack', 'overlay-pack']) {
        if (!existsSync(`${parked}/${name}`)) continue;
        cpSync(`${parked}/${name}`, `${packs}/${name}`, { recursive: true });
        rmSync(`${parked}/${name}`, { recursive: true, force: true });
      }
    };
    cleanup(async () => {
      await restore();
      await expect.poll(async () => (await consoleCommand(server, 'pm status')).includes(`Pack URL: ${PACK_URL}`),
        { timeout: 20_000 }).toBe(true);
    });
    const offer = await nextResourcePackOffer(player.bot);
    answerResourcePack(player.bot, offer.uuid, 'accepted');
    answerResourcePack(player.bot, offer.uuid, 'loaded');
    const removed = new Promise<string>(resolve =>
      player.bot._client.once('remove_resource_pack', (d: any) => resolve(String(d.uuid))));

    mkdirSync(parked, { recursive: true });
    const mark = serverLogMark(server);
    // Copy + delete, not rename: Windows refuses to rename a folder PackMerger's file watcher watches.
    for (const name of ['base-pack', 'overlay-pack']) {
      cpSync(`${packs}/${name}`, `${parked}/${name}`, { recursive: true });
      rmSync(`${packs}/${name}`, { recursive: true, force: true });
    }
    await expect(server).toHaveReceivedMessage(
      'No resource packs left to merge; players are no longer sent the previous pack', { since: mark, timeout: 20_000 });
    expect(await removed).toBe(offer.uuid);
    expect(await consoleCommand(server, 'pm status')).toContain('Pack URL: N/A');

    // A player joining now is not sent the withdrawn pack (and so cannot be kicked over it)...
    await player.rejoin();
    expect(await offeredWithin(player.bot, 6_000)).toBe('not offered');
    // ...and gets the next pack as soon as it is published.
    const next = nextResourcePackOffer(player.bot, 20_000);
    await restore();
    const back = await next;
    expect(back.hash).toBe((await downloadPack()).sha1);
    await expect(server).toHaveReceivedMessage(
      `Sending the newly published pack to ${player.username} (joined before a pack was available)`);
  });

// Zero-config enforcement (required + kick on decline), switched on for these tests only: the
// fixture keeps the old non-forcing settings so the other specs can decline freely.
function setRequired(text: string, on: boolean): string {
  return text
    .replace(/^( {2}required: )(true|false)/m, `$1${on}`)
    .replace(/^( {2}enforce-required:\n {4}enabled: )(true|false)/m, `$1${on}`);
}

async function withRequired(server: ServerWrapper, cleanup: (fn: () => Promise<void>) => void): Promise<void> {
  cleanup(() => editConfig(server, t => setRequired(t, false)));
  await editConfig(server, t => setRequired(t, true));
  await expect.poll(async () => (await consoleCommand(server, 'pm status')).includes(`Pack URL: ${PACK_URL}`),
    { timeout: 20_000 }).toBe(true);
}

test('with the zero-config defaults a player who declines the pack is kicked', async ({ server, cleanup, createPlayer }) => {
  await withRequired(server, cleanup);
  const decliner = await createPlayer();
  const offer = await nextResourcePackOffer(decliner.bot);
  expect(offer.forced).toBe(true);
  const kicked = new Promise<string>(resolve => decliner.bot.once('kicked', (reason: any) => resolve(JSON.stringify(reason))));
  answerResourcePack(decliner.bot, offer.uuid, 'declined');
  expect(await kicked).toContain('requires its resource pack');
  await expect(server).toHaveReceivedMessage(`${decliner.username} declined the resource pack`);
});

test('with the zero-config defaults a player who accepts and loads the pack stays', async ({ server, cleanup, createPlayer }) => {
  await withRequired(server, cleanup);
  const p = await createPlayer();
  const offer = await nextResourcePackOffer(p.bot);
  expect(offer.forced).toBe(true);
  expect(offer.hash).toBe((await downloadPack()).sha1);
  answerResourcePack(p.bot, offer.uuid, 'accepted');
  answerResourcePack(p.bot, offer.uuid, 'downloaded');
  answerResourcePack(p.bot, offer.uuid, 'loaded');
  await expect(server).toHaveReceivedMessage(`${p.username} successfully loaded the resource pack`);
  await sleep(3_000);
  await expect(server).not.toHaveReceivedMessage(`${p.username} lost connection`);
  expect(p.bot.entity).toBeTruthy();
});
