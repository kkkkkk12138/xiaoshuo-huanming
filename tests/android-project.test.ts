import { readFile } from 'node:fs/promises';
import { describe, expect, it } from 'vitest';

describe('Android project', () => {
  it('pins the supported SDK and app identity', async () => {
    const build = await readFile('android/app/build.gradle.kts', 'utf8');
    expect(build).toContain('namespace = "com.xiaoshuo.yijianhuanming"');
    expect(build).toContain('compileSdk = 36');
    expect(build).toContain('minSdk = 26');
    expect(build).toContain('targetSdk = 36');
    expect(build).toContain('versionCode = 3');
    expect(build).toContain('versionName = "0.2.0"');
  });

  it('registers the Room migration without destructive fallback', async () => {
    const module = await readFile(
      'android/app/src/main/java/com/xiaoshuo/yijianhuanming/di/AppModule.kt',
      'utf8',
    );

    expect(module).toContain('.addMigrations(MIGRATION_1_2)');
    expect(module).not.toContain('fallbackToDestructiveMigration');
  });

  it('does not request sensitive permissions', async () => {
    const manifest = await readFile('android/app/src/main/AndroidManifest.xml', 'utf8');
    expect(manifest).toContain('android.permission.INTERNET');
    expect(manifest).not.toMatch(
      /READ_CONTACTS|ACCESS_FINE_LOCATION|SYSTEM_ALERT_WINDOW|BIND_ACCESSIBILITY_SERVICE|RECORD_AUDIO/,
    );
  });
});
