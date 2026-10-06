const path = require('node:path');
const { getDefaultConfig } = require('expo/metro-config');

const config = getDefaultConfig(__dirname);
// Import the same connection core the web client uses, without installing a
// second React/native dependency tree from the web workspace.
config.watchFolders = [path.resolve(__dirname, '../../web/packages/engine-client')];
config.resolver.nodeModulesPaths = [path.resolve(__dirname, 'node_modules')];
module.exports = config;
