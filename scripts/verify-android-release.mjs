import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { access, copyFile, mkdir, readFile, writeFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';

const root = resolve(import.meta.dirname, '..');
const sourceApk = join(root, 'android/app/build/outputs/apk/release/app-release.apk');
const outputApk = join(root, 'dist/小说一键换名-android-0.2.0.apk');
const checksumFile = `${outputApk}.sha256`;

function fail(message) {
  throw new Error(`Android release verification failed: ${message}`);
}

function androidTool(name) {
  const sdk = process.env.ANDROID_HOME ?? process.env.ANDROID_SDK_ROOT ?? join(homedir(), 'Library/Android/sdk');
  return join(sdk, 'build-tools', '36.0.0', name);
}

function run(tool, args) {
  try {
    return execFileSync(tool, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
  } catch (error) {
    const detail = error.stderr?.toString().trim() || error.message;
    fail(`${tool} 执行失败：${detail}`);
  }
}

async function requireFile(path, label) {
  try {
    await access(path);
  } catch {
    fail(`缺少${label}：${path}`);
  }
}

await requireFile(sourceApk, 'Release APK');
for (const document of [
  'android/README.md',
  'android/signing/README.md',
  'docs/android/PRIVACY.md',
  'docs/android/INSTALL.md',
  'docs/android/THIRD_PARTY_NOTICES.md',
]) {
  await requireFile(join(root, document), '发布文档');
}

const applicationSource = await readFile(
  join(root, 'android/app/src/main/java/com/xiaoshuo/yijianhuanming/NameReplacerApp.kt'),
  'utf8',
);
if (!applicationSource.includes('WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)')) {
  fail('未显式保证 Release WebView 调试关闭');
}

const permissions = run(androidTool('aapt'), ['dump', 'permissions', sourceApk]);
const requestedPermissions = [...permissions.matchAll(/uses-permission(?:-sdk-\d+)?: name='([^']+)'/g)]
  .map((match) => match[1]);
const platformPermissions = requestedPermissions
  .filter((permission) => permission.startsWith('android.permission.'));
if (
  platformPermissions.length !== 1 ||
  platformPermissions[0] !== 'android.permission.INTERNET'
) {
  fail(`Release 平台权限必须且只能是 INTERNET，实际为：${platformPermissions.join(', ') || '无'}`);
}

const signature = run(androidTool('apksigner'), ['verify', '--verbose', '--print-certs', sourceApk]);
if (!/Verified using v\d scheme.*true/i.test(signature)) {
  fail('APK 未通过 release 证书签名验证');
}
if (/Android Debug/i.test(signature)) {
  fail('APK 使用了 Android Debug 证书');
}

const entries = run('/usr/bin/unzip', ['-Z1', sourceApk]).split(/\r?\n/);
const runtimeEntries = entries.filter((entry) => entry.endsWith('/name-replacer.js'));
if (runtimeEntries.length !== 1) {
  fail(`APK 中 name-replacer.js 数量应为 1，实际为 ${runtimeEntries.length}`);
}

await mkdir(dirname(outputApk), { recursive: true });
await copyFile(sourceApk, outputApk);
const digest = createHash('sha256').update(await readFile(outputApk)).digest('hex');
await writeFile(checksumFile, `${digest}  ${outputApk.split('/').at(-1)}\n`, 'utf8');

console.log(`Release APK verified: ${outputApk}`);
console.log(`SHA-256: ${digest}`);
