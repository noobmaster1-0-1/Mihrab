import { NativeModules, Platform } from 'react-native';
type PrayerCallNative = { startTestCall(prayer: string): Promise<void> };
const native = Platform.OS === 'android' ? (NativeModules as Record<string, PrayerCallNative | undefined>).PrayerCall : undefined;
export const prayerCallAvailable = native != null;
export async function startPrayerCallTest(prayer: string): Promise<void> {
  if (!native) throw new Error('Prayer call alerts are unavailable on this device.');
  await native.startTestCall(prayer);
}
