package fr.tomda.mcbridge.reflect;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Registre des objets Java non serialisables renvoyes par la reflexion.
 *
 * <p>Deux espaces : les poignees opaques {@code obj_N} (creees automatiquement) et les variables
 * nommees {@code $nom} (creees par {@code assignTo}). Les deux se resolvent avec {@link #resolve}.
 * Quand la limite de poignees est atteinte, les plus anciennes sont supprimees.
 */
public final class ObjectRegistry {
	private final Map<String, Object> handles = new LinkedHashMap<>();
	private final Map<String, Object> variables = new HashMap<>();
	private final AtomicLong counter = new AtomicLong();
	private final int maxRefs;

	public ObjectRegistry(int maxRefs) {
		this.maxRefs = Math.max(10, maxRefs);
	}

	public synchronized String store(Object obj) {
		while (handles.size() >= maxRefs) {
			String oldest = handles.keySet().iterator().next();
			handles.remove(oldest);
		}
		String id = "obj_" + counter.incrementAndGet();
		handles.put(id, obj);
		return id;
	}

	public synchronized void assign(String name, Object value) {
		variables.put(name, value);
	}

	/** {@code $nom} vers variable, {@code obj_N} vers poignee, sinon null. */
	public synchronized Object resolve(String ref) {
		if (ref == null) return null;
		if (ref.startsWith("$")) return variables.get(ref.substring(1));
		return handles.get(ref);
	}

	public synchronized boolean has(String ref) {
		if (ref == null) return false;
		if (ref.startsWith("$")) return variables.containsKey(ref.substring(1));
		return handles.containsKey(ref);
	}

	public synchronized void delete(String name) {
		variables.remove(name);
	}

	public synchronized void clear() {
		handles.clear();
		variables.clear();
	}

	public synchronized Map<String, Object> variables() {
		return Map.copyOf(variables);
	}
}
