{
  description = "Euporious development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
      in
      {
        devShells.default = pkgs.mkShell {
          buildInputs = [
            pkgs.tailwindcss_4
            pkgs.pnpm
          ];

          shellHook = ''
            # Store project root
            export PROJECT_ROOT="$(pwd)"

            # Set tomato-colored prompt with relative paths
            export PS1='\[\033[38;2;255;99;71m\]euporious\[\033[0m\]:''${PWD#$PROJECT_ROOT}\$ '

            echo "🌶️  Euporious development environment loaded"
            echo "Tailwind 4 CLI available: $(tailwindcss --help > /dev/null 2>&1 && echo "✓" || echo "✗")"
          '';
        };

        # Deploy to production (the netcup-vps-2 host) from the dev machine.
        #   nix run .#deploy-prod
        # The server checkout lives at
        # /home/phylax/projects/euporious_public/euporious_public and is
        # redeployed by /etc/nixos/scripts/euporious_public_redeploy.sh (part of
        # the netcup-vps-2 repo): it stops euporious_public.service, resets to
        # origin/main, rebuilds the uberjar, and restarts. Because that script
        # deploys whatever is on origin/main, we push main first, then tag the
        # deployed commit.
        apps.deploy-prod = flake-utils.lib.mkApp {
          drv = pkgs.writeShellApplication {
            name = "euporious-public-deploy-prod";
            runtimeInputs = [ pkgs.openssh pkgs.git pkgs.coreutils ];
            text = ''
              SERVER="vps-2"
              echo "=== Checking local main vs origin/main ==="
              git fetch origin main >/dev/null 2>&1 || true
              LOCAL_MAIN=$(git rev-parse main)
              REMOTE_MAIN=$(git rev-parse origin/main 2>/dev/null || echo "")
              if [ "$LOCAL_MAIN" != "$REMOTE_MAIN" ]; then
                echo "Local main ($LOCAL_MAIN) differs from origin/main ($REMOTE_MAIN)."
                read -r -p "Force push main to origin first? [y/N] " ANSWER
                case "$ANSWER" in
                  [yY]|[yY][eE][sS])
                    echo "=== Force pushing main ==="
                    git push --force-with-lease origin main
                    ;;
                  *)
                    echo "Skipping push. Server will deploy whatever is currently on origin/main."
                    ;;
                esac
              else
                echo "main is up to date with origin/main."
              fi
              echo "=== Deploying to production ($SERVER) ==="
              echo "Note: the service is down while the uberjar builds; in-memory OTS secrets are lost."
              DEPLOYED_SHA=$(ssh "$SERVER" '
                set -euo pipefail
                bash /etc/nixos/scripts/euporious_public_redeploy.sh >&2
                git -C /home/phylax/projects/euporious_public/euporious_public rev-parse HEAD
              ')
              echo "=== Tagging deployed commit $DEPLOYED_SHA ==="
              TAG="deployed-$(date -u +%Y%m%dT%H%M%SZ)"
              git tag "$TAG" "$DEPLOYED_SHA"
              git push origin "$TAG"
              echo "=== Done ($TAG) ==="
            '';
          };
        };
      }
    );
}
