function isRecord(value) {
    return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function isMeshColor(value) {
    return isRecord(value) && Number.isSafeInteger(value.id) && value.id > 0
        && typeof value.name === 'string' && value.name.trim().length > 0
        && (value.hexCode == null || /^#[\da-f]{6}$/i.test(value.hexCode));
}

function adaptMeshAppearances(value) {
    if (!Array.isArray(value)) return [];
    const seen = new Set();
    return value.filter(item => {
        if (!isRecord(item) || !Number.isSafeInteger(item.nodeIndex) || item.nodeIndex < 0
            || !Number.isSafeInteger(item.meshIndex) || item.meshIndex < 0
            || !Number.isSafeInteger(item.primitiveIndex) || item.primitiveIndex < 0
            || typeof item.customizable !== 'boolean' || !isMeshColor(item.defaultColor)
            || !Array.isArray(item.allowedColors) || !Number.isSafeInteger(item.materialId) || item.materialId < 1
            || typeof item.materialName !== 'string') return false;
        const key = `${item.nodeIndex}:${item.meshIndex}:${item.primitiveIndex}`;
        if (seen.has(key)) return false;
        const ids = item.allowedColors.map(color => color?.id);
        if (!item.allowedColors.every(isMeshColor) || new Set(ids).size !== ids.length
            || (item.customizable && (!ids.length || !ids.includes(item.defaultColor.id)))
            || (!item.customizable && ids.length > 0)) return false;
        seen.add(key);
        return true;
    });
}

function adaptProductSummary(item) {
    if (!isRecord(item) || (typeof item.id !== 'number' && typeof item.id !== 'string') || typeof item.name !== 'string') {
        throw new Error('Invalid product item response');
    }

    return {
        id: item.id,
        title: item.name,
        slug: typeof item.slug === 'string' ? item.slug : null,
        description: typeof item.description === 'string' ? item.description : '',
        category: typeof item.category === 'string' ? item.category : null,
        visibility: 'public-approved',
        publicMedia: {
            images: typeof item.thumbnailUrl === 'string' && item.thumbnailUrl.trim() ? [item.thumbnailUrl] : [],
            videoUrl: null,
            modelUrl: null,
        },
    };
}

export function adaptProductPage(payload) {
    if (!isRecord(payload)
        || !Array.isArray(payload.items)
        || !Number.isInteger(payload.page) || payload.page < 0
        || !Number.isInteger(payload.size) || payload.size < 1
        || !Number.isSafeInteger(payload.totalElements) || payload.totalElements < 0
        || !Number.isInteger(payload.totalPages) || payload.totalPages < 0) {
        throw new Error('Invalid product page response');
    }

    return {
        items: payload.items.map(adaptProductSummary),
        page: payload.page,
        size: payload.size,
        totalElements: payload.totalElements,
        totalPages: payload.totalPages,
    };
}

export function adaptProductDetail(payload) {
    const product = adaptProductSummary(payload);
    const images = Array.isArray(payload.images)
        ? payload.images.filter(image => isRecord(image) && typeof image.url === 'string' && image.url.trim()).map(image => image.url)
        : [];
    const modelParts = Array.isArray(payload.modelParts)
        ? payload.modelParts.filter(part => isRecord(part) && typeof part.name === 'string').map(part => ({ id: part.key || part.name, name: part.name }))
        : [];

    return {
        ...product,
        publicMedia: {
            images: images.length ? images : product.publicMedia.images,
            videoUrl: null,
            modelUrl: typeof payload.model3dUrl === 'string' && payload.model3dUrl.trim() ? payload.model3dUrl : null,
        },
        publicModelDimensions: [payload.sizeXMm, payload.sizeYMm, payload.sizeZMm].every(Number.isFinite)
            ? { x: payload.sizeXMm, y: payload.sizeYMm, z: payload.sizeZMm }
            : null,
        publicModelParts: modelParts,
        modelMeshAppearances: adaptMeshAppearances(payload.modelMeshAppearances),
    };
}

export function isProductNotFound(error) {
    return error?.response?.status === 404;
}

export function getProductLoadErrorMessage(resource = 'catalog') {
    return resource === 'detail'
        ? 'Không thể tải thông tin sản phẩm. Vui lòng thử lại.'
        : 'Không thể tải danh mục sản phẩm. Vui lòng thử lại.';
}

export function getCatalogState({ loading, error, items = [] }) {
    if (loading) return 'loading';
    if (error) return 'error';
    return items.length ? 'ready' : 'empty';
}

export function getProductDetailState({ loading, notFound, error, product }) {
    if (loading) return 'loading';
    if (notFound) return 'not-found';
    if (error) return 'error';
    return product ? 'ready' : 'empty';
}
