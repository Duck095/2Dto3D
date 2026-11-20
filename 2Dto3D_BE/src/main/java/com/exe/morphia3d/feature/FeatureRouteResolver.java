package com.exe.morphia3d.feature;

import java.util.Map;
import java.util.Optional;
import java.util.List;

public final class FeatureRouteResolver {
    private static final Map<String, FeatureFamily> ROUTES = Map.ofEntries(
            Map.entry("/api/ai-models", FeatureFamily.AI),
            Map.entry("/api/ai-studio/generations", FeatureFamily.AI),
            Map.entry("/api/ai-studio/projects", FeatureFamily.AI),
            Map.entry("/api/quotes", FeatureFamily.QUOTE),
            Map.entry("/api/quote", FeatureFamily.QUOTE),
            Map.entry("/api/price-estimates", FeatureFamily.QUOTE),
            Map.entry("/api/cart", FeatureFamily.CART),
            Map.entry("/api/orders", FeatureFamily.ORDER),
            Map.entry("/api/checkout", FeatureFamily.CHECKOUT),
            Map.entry("/api/checkouts", FeatureFamily.CHECKOUT),
            Map.entry("/api/payments", FeatureFamily.PAYMENT));

    private FeatureRouteResolver() {}

    public static Optional<FeatureFamily> familyFor(String path) {
        if (path == null) return Optional.empty();
        return ROUTES.entrySet().stream()
                .filter(route -> path.equals(route.getKey()) || path.startsWith(route.getKey() + "/"))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    public static List<FeatureFamily> requiredFamiliesFor(String method, String path) {
        Optional<FeatureFamily> routeFamily = familyFor(path);
        if (routeFamily.isEmpty()) return List.of();
        if (routeFamily.get() == FeatureFamily.ORDER && isCheckoutCreationRoute(method, path)) {
            return List.of(FeatureFamily.ORDER, FeatureFamily.CHECKOUT);
        }
        return List.of(routeFamily.get());
    }

    private static boolean isCheckoutCreationRoute(String method, String path) {
        return "POST".equalsIgnoreCase(method)
                && ("/api/orders".equals(path) || path.matches("/api/orders/from-cart-item/[^/]+"));
    }
}
