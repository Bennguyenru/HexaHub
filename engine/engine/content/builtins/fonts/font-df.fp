varying mediump vec2 var_texcoord0;
varying lowp vec4 var_face_color;
varying lowp vec4 var_outline_color;
varying mediump vec4 var_sdf_params;
uniform lowp sampler2D texture;
uniform lowp vec4 texture_size_recip;

float sample_df(vec2 where)
{   
    return texture2D(texture, where).x;
}

vec2 get_alphas(float distance)
{
    lowp float sdf_edge = var_sdf_params.x;
    lowp float sdf_outline = var_sdf_params.y;
    lowp float sdf_smoothing = var_sdf_params.z;

    lowp float alpha = smoothstep(sdf_edge - sdf_smoothing, sdf_edge + sdf_smoothing, distance);
    lowp float outline_alpha = smoothstep(sdf_outline - sdf_smoothing, sdf_outline + sdf_smoothing, distance);
    
    return vec2(alpha, outline_alpha);
}

void main_supersample()
{
    lowp vec2 dtex = vec2(0.5 * texture_size_recip.xy);
    // sample 4 points around var_texcoord0
    lowp vec4 dt = vec4(vec2(var_texcoord0 - dtex), vec2(var_texcoord0 + dtex));
    lowp vec2 alphas = 2.0 * get_alphas(sample_df(var_texcoord0))
                   + get_alphas(sample_df(dt.xy)) // upper left
                   + get_alphas(sample_df(dt.xw)) // bottom left
                   + get_alphas(sample_df(dt.zy)) // upper right
                   + get_alphas(sample_df(dt.zw)); // bottom right
    alphas = (1.0 / 6.0) * alphas;
    lowp vec4 color = mix(var_outline_color, var_face_color, alphas.x);
    gl_FragColor = color * alphas.y;
}

void main_default()
{
    lowp vec2 alphas = get_alphas(sample_df(var_texcoord0));
    lowp vec4 color = mix(var_outline_color, var_face_color, alphas.x);
    gl_FragColor = color * alphas.y;
}

void main()
{
    main_default();
}