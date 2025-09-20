import {createApi, fetchBaseQuery} from '@reduxjs/toolkit/query/react'

const getCookie = (name) => {
    const cookies = document.cookie.split(';');
    for (let i = 0; i < cookies.length; i++) {
        let cookie = cookies[i].trim();
        if (cookie.startsWith(name + '=')) {
            return cookie.substring(name.length + 1);
        }
    }
    return null;
}

const baseQuery = fetchBaseQuery({
    baseUrl: process.env.REACT_APP_BACKEND_URI + '/api/',
    prepareHeaders: (headers) => {
        const token = getCookie('XSRF-TOKEN');
        if (token) {
            headers.set('X-XSRF-TOKEN', token);
        }
        return headers;
    },
});


export const eventBotApi = createApi({
    reducerPath: 'eventBot',
    baseQuery: baseQuery,
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
    useIsLoggedInQuery
} = eventBotApi
