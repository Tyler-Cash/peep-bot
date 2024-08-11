import {configureStore} from '@reduxjs/toolkit'

import rootReducer from './reducers/rootReducer'
import {eventBotApi} from "./api/eventBotApi";

export default function configureAppStore(preloadedState) {
    const store = configureStore({
        reducer: rootReducer,
        middleware: (getDefaultMiddleware) =>
            getDefaultMiddleware().concat(eventBotApi.middleware),
        preloadedState,
        enhancers: (getDefaultEnhancers) =>
            getDefaultEnhancers(),
    })

    if (process.env.NODE_ENV !== 'production' && module.hot) {
        module.hot.accept('./reducers/rootReducer', () => store.replaceReducer(rootReducer))
    }

    return store
}

