package fr.tomda.mcbridge.bridge;

/** Fournisseur de valeur autorise a lever n'importe quelle exception (utilise pour les taches thread principal). */
@FunctionalInterface
public interface ThrowingSupplier<T> {
	T get() throws Exception;
}
