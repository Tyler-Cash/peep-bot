import {createApi, fetchBaseQuery} from '@reduxjs/toolkit/query/react'


export const eventBotApi = createApi({
    reducerPath: 'eventBot',
    baseQuery: fetchBaseQuery({baseUrl: process.env.REACT_APP_BACKEND_URI + '/api/'}),
    refetchOnMountOrArgChange: 120,
    endpoints: (builder) => ({
        getEvents: builder.query({
            query: () => `event`,
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
                    "dateTime": data.dateTime
                },
            }),
        }),
        isLoggedIn: builder.query({
            query: () => `auth/is-logged-in`,
        }),
    }),
})

export const {useGetEventsQuery, useDeleteEventQuery, useCreateEventMutation, useIsLoggedInQuery} = eventBotApi