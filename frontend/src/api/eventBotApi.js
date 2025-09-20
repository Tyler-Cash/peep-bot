import {createApi, fetchBaseQuery} from '@reduxjs/toolkit/query/react'


export const eventBotApi = createApi({
    reducerPath: 'eventBot',
    baseQuery: fetchBaseQuery({
        baseUrl: import.meta.env.VITE_BACKEND_URI + '/api/'
    }),
    credentials: "include",
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
