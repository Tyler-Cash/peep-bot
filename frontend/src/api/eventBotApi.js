import {createApi, fetchBaseQuery} from '@reduxjs/toolkit/query/react'

// In-memory cache of the CSRF token for this tab/session.
// You could also store it in Redux state if you prefer.
let csrfTokenCache = null;

// A small helper that fetches the CSRF token from the backend once.
// Assumes the backend exposes GET /api/csrf that returns: { token: "..." }
async function ensureCsrfToken(baseUrl) {
    if (csrfTokenCache) return csrfTokenCache;

    const res = await fetch(`${baseUrl}csrf`, {
        method: 'GET',
        credentials: 'include',
        headers: {'Accept': 'application/json'},
    });

    if (!res.ok) {
        throw new Error(`Failed to fetch CSRF token: ${res.status}`);
    }

    // You can also read from a header (e.g., res.headers.get('X-CSRF-TOKEN')) if the backend provides it there
    const data = await res.json();
    csrfTokenCache = data?.token ?? null;
    if (!csrfTokenCache) {
        throw new Error('CSRF token missing in response');
    }
    return csrfTokenCache;
}

const baseUrl = `${import.meta.env.VITE_BACKEND_URI}/api/`;

const baseQuery = fetchBaseQuery({
    baseUrl,
    // Make sure cookies/session are sent cross-site
    credentials: 'include',
    prepareHeaders: (headers) => {
        // Attach token if we already have it
        if (csrfTokenCache) {
            headers.set('X-XSRF-TOKEN', csrfTokenCache);
        }
        return headers;
    },
});

// A wrapper that ensures the CSRF token is loaded before mutating requests
const baseQueryWithCsrf = async (args, api, extraOptions) => {
    const method =
        typeof args === 'string'
            ? 'GET'
            : (args?.method || args?.body?.method || args?.params?.method || (args?.body ? 'POST' : 'GET')).toUpperCase();

    // Only ensure CSRF for state-changing requests
    const needsCsrf = ['POST', 'PUT', 'PATCH', 'DELETE'].includes(method);

    if (needsCsrf && !csrfTokenCache) {
        try {
            await ensureCsrfToken(baseUrl);
        } catch (e) {
            // Surface a meaningful error to RTK Query
            return {error: {status: 'CSRF_FETCH_FAILED', data: String(e)}};
        }
    }

    // Proceed with the actual request; prepareHeaders will add X-XSRF-TOKEN if available
    return baseQuery(args, api, extraOptions);
};

export const eventBotApi = createApi({
    reducerPath: 'eventBot',
    baseQuery: baseQueryWithCsrf,
    refetchOnMountOrArgChange: 120,
    endpoints: (builder) => ({
        getEvents: builder.query({
            query: () => `event`,
        }),
        getEvent: builder.query({
            query: ({id}) => `event/${id}`,
        }),
        deleteEvent: builder.mutation({
            query: ({id}) => ({
                url: `event`,
                method: 'DELETE',
                params: {id: id},
            }),
        }),
        createEvent: builder.mutation({
            query: (data) => ({
                url: `event`,
                method: 'PUT',
                body: {
                    "name": data.name,
                    "description": data.description,
                    "location": data.location,
                    "capacity": data.capacity,
                    "cost": data.cost,
                    "dateTime": data.dateTime,
                    "notifyOnCreate": data.notifyOnCreate ?? true
                },
            }),
        }),
        patchEvent: builder.mutation({
            query: (data) => ({
                url: `event`,
                method: 'PATCH',
                body: {
                    "id": data.id,
                    "name": data.name,
                    "description": data.description,
                    "capacity": data.capacity,
                    "dateTime": data.dateTime,
                    "accepted": data.accepted,
                },
            }),
        }),
        isLoggedIn: builder.query({
            query: () => `auth/is-logged-in`,
        }),
    }),
})

export const {
    useGetEventsQuery,
    useGetEventQuery,
    useCreateEventMutation,
    usePatchEventMutation,
    useIsLoggedInQuery,
} = eventBotApi;
