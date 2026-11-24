// PM2 : process séparé du site beliandjolie (ne jamais les redémarrer ensemble)
module.exports = {
  apps: [
    {
      name: 'scanner-caisse',
      cwd: '/var/www/scanner-caisse',
      script: 'src/main.js',
      node_args: '--env-file=.env',
      instances: 1,
      exec_mode: 'fork',
      max_memory_restart: '200M',
      time: true,
    },
  ],
};
