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
      }
    );
}
