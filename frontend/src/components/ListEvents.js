import React from 'react';
import EventOverview from "./EventOverview";
import {useGetEventsQuery} from "../api/eventBotApi";
import Navbar from "./Navbar";

export default function ListEvents(props) {
    const {data, error, isLoading} = useGetEventsQuery()

    let navbar = <Navbar focus="LIST"/>;
    if (data == null) {
        return (
            <div>
                {navbar}
                <p>Loading events...</p>
            </div>
        );
    } else if (data.length > 0) {
        return (
            <div>
                {navbar}
                <div className="container">
                    <div className="row-gap-3">
                        {data.map((event) => {
                            return (<EventOverview key={event.id}
                                                   id={event.id}
                                                   name={event.name}
                                                   description={event.description}
                                                   date={event.dateTime}
                            />)
                        })}
                    </div>
                </div>
            </div>
        );
    } else {
        return (
            <div>
                {navbar}
                <p>No events, create one!</p>
            </div>
        );
    }
}

export {ListEvents}
