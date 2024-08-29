import React from 'react';
import {Link} from "react-router-dom";
import moment from "moment-timezone";

export default function EventOverview(props) {
    return (
        <div href="#" className="p-4 mt-5 border" aria-current="true">
            <div className="position-relative">
                <div className="fw-bolder text-start position-absolute top-0 start-0">{props.name}</div>
                <div className="text-end opacity-75 position-absolute top-0 end-0">
                    <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" fill="currentColor"
                         className="bi bi-clock me-1" viewBox="0 0 16 16">
                        <path d="M8 3.5a.5.5 0 0 0-1 0V9a.5.5 0 0 0 .252.434l3.5 2a.5.5 0 0 0 .496-.868L8 8.71z"/>
                        <path d="M8 16A8 8 0 1 0 8 0a8 8 0 0 0 0 16m7-8A7 7 0 1 1 1 8a7 7 0 0 1 14 0"/>
                    </svg>
                    {moment(props.date).tz(moment.tz.guess(true)).format(" LT - dddd Do of MMMM")}
                </div>
            </div>
            <br/>
            <p className="mb-0 opacity-75">{props.description}</p>
            <br/>
            <div role="group" className="btn-group w-10">
                <Link to={"/event/" + props.id}>
                    <button type="button" className="btn-secondary p-2 btn">
                        <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" fill="currentColor"
                             className="bi bi-pencil me-1" viewBox="0 0 16 16">
                            <path
                                d="M12.146.146a.5.5 0 0 1 .708 0l3 3a.5.5 0 0 1 0 .708l-10 10a.5.5 0 0 1-.168.11l-5 2a.5.5 0 0 1-.65-.65l2-5a.5.5 0 0 1 .11-.168zM11.207 2.5 13.5 4.793 14.793 3.5 12.5 1.207zm1.586 3L10.5 3.207 4 9.707V10h.5a.5.5 0 0 1 .5.5v.5h.5a.5.5 0 0 1 .5.5v.5h.293zm-9.761 5.175-.106.106-1.528 3.821 3.821-1.528.106-.106A.5.5 0 0 1 5 12.5V12h-.5a.5.5 0 0 1-.5-.5V11h-.5a.5.5 0 0 1-.468-.325"/>
                        </svg>
                        Edit
                    </button>
                </Link>
            </div>
            <br/>
        </div>
    );
}