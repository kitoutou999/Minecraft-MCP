package fr.tomda.mcbridge.reflect;

import java.util.List;

/**
 * Liste d'autorisation / de blocage des classes accessibles par reflexion.
 * Un motif se termine par {@code .*} pour couvrir un package, sinon il designe une classe exacte.
 * Le blocage est prioritaire sur l'autorisation.
 */
public final class PackageFilter {
	private final List<String> allowed;
	private final List<String> blocked;

	public PackageFilter(List<String> allowed, List<String> blocked) {
		this.allowed = allowed;
		this.blocked = blocked;
	}

	public boolean isAllowed(String className) {
		if (matches(className, blocked)) return false;
		return matches(className, allowed);
	}

	private static boolean matches(String className, List<String> patterns) {
		if (patterns == null) return false;
		for (String pattern : patterns) {
			if (pattern.endsWith(".*")) {
				String pkg = pattern.substring(0, pattern.length() - 2);
				if (className.equals(pkg) || className.startsWith(pkg + ".")) return true;
			} else if (className.equals(pattern)) {
				return true;
			}
		}
		return false;
	}
}
