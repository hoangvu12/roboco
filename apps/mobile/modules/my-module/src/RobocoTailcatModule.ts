import { NativeModule, requireOptionalNativeModule } from 'expo';

declare class RobocoTailcatModule extends NativeModule<{}> {
  openRoute(address: string, derpMap: string): Promise<string>;
  loadConnection(): Promise<string | null>;
  saveConnection(json: string): Promise<void>;
  forgetConnection(): Promise<void>;
  disconnect(): void;
}

export default requireOptionalNativeModule<RobocoTailcatModule>('RobocoTailcat');
