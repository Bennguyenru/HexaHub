varying mediump vec2 var_texcoord0;
varying lowp float   var_page_index;

uniform lowp sampler2DArray texture_sampler;
uniform lowp vec4           tint;

void main()
{
    // Pre-multiply alpha since all runtime textures already are
    lowp vec4 tint_pm = vec4(tint.xyz * tint.w, tint.w);
    gl_FragColor      = texture2DArray(texture_sampler, vec3(var_texcoord0.xy, var_page_index)) * tint_pm;
}
