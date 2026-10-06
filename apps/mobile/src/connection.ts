import Tailcat from '../modules/my-module';
import { createConnector } from './connection-core';
export { Connection } from './connection-core';

function native() {
  if (!Tailcat) throw new Error('Install the Roboco Android development build; Expo Go does not include its native transport.');
  return Tailcat;
}

const connector = createConnector({
  load: () => native().loadConnection(),
  save: json => native().saveConnection(json),
  forget: () => native().forgetConnection(),
  openRoute: (address, derpMap) => native().openRoute(address, derpMap),
  closeRoute: () => native().disconnect(),
});

export const connect = connector.connect;
export const restore = connector.restore;
export const forget = connector.forget;
