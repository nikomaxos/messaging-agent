const gitCmds = `
      git config --global --add safe.directory /repo &&
      git config --global user.email "deploy-agent@messaging-agent.local" &&
      git config --global user.name "Deploy Agent" &&
      git remote set-url origin git@github.com:nikomaxos/messaging-agent.git &&
      npm --prefix admin-panel version patch --no-git-tag-version &&
      NEW_VERSION=\$(node -e "console.log(require('./admin-panel/package.json').version)") &&
      ./bump-version.sh \$NEW_VERSION &&
      git add . &&
      (git commit -m "Auto-Deploy: Pushed from Admin Panel (v\$NEW_VERSION)" || true) &&
      GIT_SSH_COMMAND="ssh -o StrictHostKeyChecking=no" git push origin main
`;
console.log(gitCmds);
