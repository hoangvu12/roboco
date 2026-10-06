const { withAndroidManifest } = require('expo/config-plugins');

module.exports = config => withAndroidManifest(config, config => {
  const application = config.modResults.manifest.application[0];
  // Pairing credentials and Tailcat identity must not migrate via backups.
  application.$['android:allowBackup'] = 'false';
  // Native Tailcat exposes loopback HTTP/WS. Direct HTTP is for trusted LANs;
  // users can also pair an HTTPS endpoint.
  application.$['android:usesCleartextTraffic'] = 'true';
  return config;
});
